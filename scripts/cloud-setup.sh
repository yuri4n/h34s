#!/usr/bin/env bash
# Prepare a Claude Code cloud session (Ubuntu 24.04) for h34s work: Java 25, sbt 1.11.7,
# Node 24 and pnpm 10.34.4, plus sbt settings that read Maven Central through a mirror.
# docs/maintainers/cloud.md explains every step. The script is idempotent: when every tool
# is present, it changes nothing and finishes in about a second.
#
# Usage: bash scripts/cloud-setup.sh [--services] [--print-env]
#   --services   also start PostgreSQL and Redis; a restarted VM does not keep them running
#   --print-env  print `export` lines for the toolchain on stdout; all other output goes to stderr
#
# Set H34S_TOOLS_DIR to change the install directory. The default is /opt/h34s-tools for
# root and ~/.local/share/h34s-tools for other users.
set -euo pipefail

readonly java_major=25
readonly sbt_version=1.11.7
# Maven Central publishes only a SHA-1 for the launcher and no checksum for the runner
# script, so the SHA-256 values are pinned here. docs/maintainers/cloud.md says how to
# check and update them.
readonly sbt_launcher_sha256=f92a2095ac75008764fe3b2b793ffe624c4fbef5bfd9b0022e4bc2daf668c651
readonly sbt_runner_sha256=d70f61cbc9fd3b4ff01d810de89dfaedfa62ecbb4b242911181cc14e6937b8e6
readonly node_version=24.22.0
readonly pnpm_version=10.34.4
# Maven Central answers the shared egress address of cloud sessions with HTTP 429 for many
# requests, and coursier does not retry a 429. Google's mirror of Maven Central does not.
readonly maven_central_mirror=https://maven-central.storage-download.googleapis.com/maven2/

# Keep stdout for --print-env; everything else goes to stderr.
exec 3>&1 1>&2

log() { printf 'cloud-setup: %s\n' "$*"; }
die() {
  log "error: $*"
  exit 1
}

usage() {
  sed -n '7,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

as_root() {
  if [ "$(id -u)" -eq 0 ]; then
    "$@"
  elif command -v sudo > /dev/null 2>&1 && sudo -n true 2> /dev/null; then
    sudo -n "$@"
  else
    die "'$*' needs root: run this script as root, or give this user passwordless sudo"
  fi
}

fetch() { # URL DESTINATION
  # curl retries transient failures, HTTP 429 included.
  curl -fsSL --retry 5 --retry-delay 2 -o "$2" "$1" || die "cannot download $1"
}

check_sha256() { # FILE EXPECTED
  local actual
  actual=$(sha256sum "$1" | cut -d ' ' -f 1)
  [ "$actual" = "$2" ] || die "SHA-256 mismatch for $1: expected $2, got $actual"
}

write_if_changed() { # FILE CONTENT
  if [ "$(cat "$1" 2> /dev/null)" != "$2" ]; then
    mkdir -p "$(dirname "$1")"
    printf '%s\n' "$2" > "$1"
  fi
}

java_major_of() { # JDK_HOME
  sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$1/release" 2> /dev/null
}

find_java() {
  local candidate
  for candidate in "${JAVA_HOME:-}" /usr/lib/jvm/java-"$java_major"-openjdk-*; do
    if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ] \
      && [ "$(java_major_of "$candidate")" = "$java_major" ]; then
      java_home=$candidate
      return 0
    fi
  done
  return 1
}

ensure_java() {
  find_java && return
  command -v apt-get > /dev/null 2>&1 || die "Java $java_major is missing, and apt-get is not available to install it"
  log "installing OpenJDK $java_major from the Ubuntu archive"
  as_root apt-get update -q
  as_root env DEBIAN_FRONTEND=noninteractive \
    apt-get install -y -q --no-install-recommends "openjdk-$java_major-jdk-headless"
  find_java || die "apt installed openjdk-$java_major-jdk-headless, but no Java $java_major home is in /usr/lib/jvm"
}

ensure_sbt() {
  sbt_home=$tools_dir/sbt-$sbt_version
  if [ ! -x "$sbt_home/bin/sbt" ] || [ ! -f "$sbt_home/bin/sbt-launch.jar" ]; then
    log "installing sbt $sbt_version"
    local staging=$work/sbt-$sbt_version
    mkdir -p "$staging/bin"
    fetch "${maven_central_mirror}org/scala-sbt/sbt-launch/$sbt_version/sbt-launch-$sbt_version.jar" \
      "$staging/bin/sbt-launch.jar"
    fetch "https://raw.githubusercontent.com/sbt/sbt/v$sbt_version/sbt" "$staging/bin/sbt"
    check_sha256 "$staging/bin/sbt-launch.jar" "$sbt_launcher_sha256"
    check_sha256 "$staging/bin/sbt" "$sbt_runner_sha256"
    chmod 755 "$staging/bin/sbt"
    rm -rf "$sbt_home"
    mv "$staging" "$sbt_home"
  fi

  # The runner reads conf/sbtopts next to itself when no /etc/sbt/sbtopts or
  # ~/.config/sbt/sbtopts exists, so every call of this sbt gets the mirror settings.
  # The repositories file covers the launcher. The global settings rewrite Maven Central to
  # the mirror in every build, without replacing the build's own resolvers, which
  # sbt.override.build.repos would do.
  local mirror_config=$tools_dir/sbt-mirror
  if [ -f /etc/sbt/sbtopts ] || [ -f "${XDG_CONFIG_HOME:-$HOME/.config}/sbt/sbtopts" ]; then
    die "a machine-wide sbtopts file exists, so sbt would ignore $sbt_home/conf/sbtopts"
  fi
  write_if_changed "$sbt_home/conf/sbtopts" "# Written by scripts/cloud-setup.sh; see docs/maintainers/cloud.md.
-Dsbt.repository.config=$mirror_config/repositories
-Dsbt.global.base=$mirror_config/global"
  write_if_changed "$mirror_config/repositories" "[repositories]
  local
  maven-central-mirror: $maven_central_mirror"
  write_if_changed "$mirror_config/global/maven-central-mirror.sbt" "// Written by scripts/cloud-setup.sh; see docs/maintainers/cloud.md.
def h34sViaMirror(resolvers: Seq[Resolver]): Seq[Resolver] = {
  val central = Set(\"https://repo1.maven.org/maven2\", \"https://repo.maven.apache.org/maven2\")
  resolvers.map {
    case r: sbt.librarymanagement.MavenRepository if central(r.root.stripSuffix(\"/\")) =>
      sbt.librarymanagement.MavenRepository(\"maven-central-mirror\", \"$maven_central_mirror\")
    case r => r
  }.distinct
}

csrResolvers ~= h34sViaMirror
csrSbtResolvers ~= h34sViaMirror
// sbt recomputes the resolvers for compiler bridges in this scope, adding plugin resolvers.
scalaCompilerBridgeScope / csrResolvers ~= h34sViaMirror"
}

ensure_node() {
  local arch
  case "$(uname -m)" in
    x86_64) arch=x64 ;;
    aarch64 | arm64) arch=arm64 ;;
    *) die "no Node build for CPU architecture $(uname -m)" ;;
  esac
  node_home=$tools_dir/node-v$node_version-linux-$arch
  [ -x "$node_home/bin/node" ] && return

  log "installing Node $node_version"
  local archive=node-v$node_version-linux-$arch.tar.xz
  fetch "https://nodejs.org/dist/v$node_version/SHASUMS256.txt" "$work/SHASUMS256.txt"
  fetch "https://nodejs.org/dist/v$node_version/$archive" "$work/$archive"
  (cd "$work" && grep " $archive\$" SHASUMS256.txt | sha256sum -c --quiet -) \
    || die "SHA-256 mismatch for $archive"
  tar -xJf "$work/$archive" -C "$work"
  rm -rf "$node_home"
  mv "$work/node-v$node_version-linux-$arch" "$node_home"
}

pnpm_version_of() { # NODE_BIN
  [ -x "$1/pnpm" ] || return 0
  (cd / && PATH="$1:$PATH" COREPACK_ENABLE_DOWNLOAD_PROMPT=0 "$1/pnpm" --version 2> /dev/null) || true
}

ensure_pnpm() {
  local bin=$node_home/bin
  [ "$(pnpm_version_of "$bin")" = "$pnpm_version" ] && return

  # Corepack checks the downloaded package against the npm registry's signing keys.
  log "installing pnpm $pnpm_version with Corepack"
  PATH="$bin:$PATH" COREPACK_ENABLE_DOWNLOAD_PROMPT=0 "$bin/corepack" enable pnpm
  PATH="$bin:$PATH" COREPACK_ENABLE_DOWNLOAD_PROMPT=0 "$bin/corepack" install --global "pnpm@$pnpm_version"
  [ "$(pnpm_version_of "$bin")" = "$pnpm_version" ] || die "Corepack did not activate pnpm $pnpm_version"
}

start_services() {
  command -v pg_isready > /dev/null 2>&1 || die "PostgreSQL is not installed"
  command -v redis-cli > /dev/null 2>&1 || die "Redis is not installed"
  as_root service postgresql start
  as_root service redis-server start
  for _ in $(seq 20); do
    pg_isready -q && break
    sleep 0.5
  done
  pg_isready -q || die "PostgreSQL does not accept connections"
  [ "$(redis-cli ping)" = PONG ] || die "Redis does not answer PING"
  log "PostgreSQL and Redis are running"
}

print_env() {
  # Literal values, without the earlier copies of these directories in PATH.
  local path_value=$java_home/bin:$node_home/bin:$sbt_home/bin entry
  local IFS=:
  for entry in $PATH; do
    [ -n "$entry" ] || continue
    case ":$path_value:" in
      *":$entry:"*) ;;
      *) path_value=$path_value:$entry ;;
    esac
  done
  printf "export JAVA_HOME='%s'\n" "$java_home"
  printf "export PATH='%s'\n" "$path_value"
  printf "export COREPACK_ENABLE_DOWNLOAD_PROMPT=0\n"
}

services=false
print=false
for argument in "$@"; do
  case $argument in
    --services) services=true ;;
    --print-env) print=true ;;
    -h | --help)
      usage >&3
      exit 0
      ;;
    *) die "unknown option '$argument' (expected --services or --print-env)" ;;
  esac
done

[ "$(uname -s)" = Linux ] \
  || die "this script prepares Linux cloud sessions; on another system, install the tools that docs/development.md lists"

if [ "$(id -u)" -eq 0 ]; then
  tools_dir=${H34S_TOOLS_DIR:-/opt/h34s-tools}
else
  tools_dir=${H34S_TOOLS_DIR:-${XDG_DATA_HOME:-$HOME/.local/share}/h34s-tools}
fi
mkdir -p "$tools_dir"
work=$tools_dir/.partial
rm -rf "$work"
mkdir -p "$work"
trap 'rm -rf "$work"' EXIT

ensure_java
ensure_sbt
ensure_node
ensure_pnpm
if $services; then start_services; fi

log "ready: Java $java_major ($java_home), sbt $sbt_version, Node $node_version, pnpm $pnpm_version"
if $print; then print_env >&3; fi
