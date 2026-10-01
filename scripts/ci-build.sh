#!/usr/bin/env bash
set -euo pipefail

pnpm install --frozen-lockfile
pnpm run scala:test
pnpm run scala:fast
node scripts/check-scala-output.mjs development
printf 'stale development output\n' > server/.scala/stale-dev.js
pnpm build
node scripts/check-scala-output.mjs production
git --no-pager diff --exit-code
node --test scripts/smoke-build.test.mjs
