#!/usr/bin/env bash
# SessionStart hook for Claude Code cloud sessions: make sure the h34s toolchain is
# installed, then give the session's shell commands its PATH and JAVA_HOME through
# CLAUDE_ENV_FILE. Outside cloud sessions, for example on a developer Mac, it does nothing.
# docs/maintainers/cloud.md explains the setup and how to debug it.
set -euo pipefail

[ "${CLAUDE_CODE_REMOTE:-}" = "true" ] || exit 0

cd "${CLAUDE_PROJECT_DIR:-$(dirname "$0")/../..}"

if ! env_lines=$(bash scripts/cloud-setup.sh --print-env); then
  # Exit 0 with JSON, so that the user sees the warning and Claude gets the context.
  printf '%s\n' '{"systemMessage":"h34s cloud setup failed; run bash scripts/cloud-setup.sh to see the error (docs/maintainers/cloud.md).","hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"The h34s SessionStart hook could not prepare the toolchain (Java 25, sbt, Node 24, pnpm). Run bash scripts/cloud-setup.sh to see the error, and read docs/maintainers/cloud.md."}}'
  exit 0
fi

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  # Append, so that other hooks keep their variables; skip lines from an earlier run.
  while IFS= read -r line; do
    grep -qxF -- "$line" "$CLAUDE_ENV_FILE" 2> /dev/null || printf '%s\n' "$line" >> "$CLAUDE_ENV_FILE"
  done <<< "$env_lines"
fi

# The repository's npm packages, so that the Node checks and the Scala.js test suites run
# without a manual install: the pnpm workspace, and the pinned npm fixtures (h3, unstorage)
# that the test suites load through Node. pnpm skips a satisfied workspace in about two
# seconds. A fixture is skipped while npm's own marker is newer than its lockfile, because
# `npm ci` always reinstalls. An install failure is reported in the context line below and
# does not hide the toolchain. Install output goes to stderr; stdout is context for Claude.
eval "$env_lines"
packages_note="and the npm packages (workspace and test fixtures) are installed"
if ! pnpm install --frozen-lockfile --ignore-scripts >&2; then
  packages_note="but 'pnpm install --frozen-lockfile --ignore-scripts' failed: run it to see why"
fi
for fixture in scala-modules/exts/*/test-js; do
  if [ "$fixture/node_modules/.package-lock.json" -nt "$fixture/package-lock.json" ]; then
    continue
  fi
  if ! npm ci --prefix "$fixture" --ignore-scripts >&2; then
    packages_note="but 'npm ci --prefix $fixture --ignore-scripts' failed: run it to see why"
  fi
done

echo "h34s cloud toolchain ready (scripts/cloud-setup.sh): Java, sbt, Node and pnpm are on PATH through CLAUDE_ENV_FILE, $packages_note. sbt and scalafix read Maven Central through Google's mirror. If a Bash command still cannot find sbt (seen once, when this hook first ran at a compaction), prefix the command with: eval \"\$(bash scripts/cloud-setup.sh --print-env)\" && ... Run one sbt process at a time, and run builds longer than a few minutes in the background. See docs/maintainers/cloud.md."
