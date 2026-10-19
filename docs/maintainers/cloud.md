# Work on h34s in Claude Code cloud sessions

This guide is for maintainers who let Claude Code work on this repository in a [cloud session](https://code.claude.com/docs/en/claude-code-on-the-web). A cloud session runs in a fresh Ubuntu 24.04 VM with this repository cloned. The VM has Java 21 and Node 22, but h34s needs Java 25, sbt 1.11.7, Node 24, and pnpm 10.34.4. A SessionStart hook in this repository installs them and prepares sbt, so a session can build and test without manual setup.

For local development on your own machine, use [development.md](../development.md) instead. Nothing in this guide changes a local setup.

## Choose the environment

Cloud sessions run in a cloud environment that you configure on claude.ai. Its **network access** level decides which hosts the session can reach.

- **Full** access works. The toolchain, sbt, Node, and service commands in this guide were verified with it; the [verification record](#verification-record) lists what was not.
- **Trusted**, the default level, should also work, but it is not verified. Every host that the setup uses is in the [Trusted default list](https://code.claude.com/docs/en/cloud-environments#default-allowed-domains):

| Host | Used for |
| --- | --- |
| `archive.ubuntu.com`, `security.ubuntu.com` | OpenJDK 25 (`apt`) |
| `nodejs.org` | Node 24 and its `SHASUMS256.txt` |
| `raw.githubusercontent.com` | The sbt runner script |
| `registry.npmjs.org` | pnpm through Corepack, `pnpm install`, `npm ci` for the test fixtures |
| `maven-central.storage-download.googleapis.com` (`*.googleapis.com` in the list) | The sbt launcher, sbt itself, and every Maven Central dependency |

If you use **Custom** access, add these hosts and keep **Also include default list of common package managers** checked. If a host is missing, the setup script stops with an error that names the URL, and sbt reports a download error that names the host.

GitHub does not need an entry. Git and `gh` reach GitHub through a separate GitHub proxy at every access level.

## What happens when a session starts

[`.claude/settings.json`](../../.claude/settings.json) registers one SessionStart hook, [`.claude/hooks/session-start.sh`](../../.claude/hooks/session-start.sh). Claude Code runs it when a session starts, resumes, clears, or compacts. In a session with several repositories, Claude Code loads no repository's hooks, so this hook does not run; see [Debug the hook](#debug-the-hook).

1. The hook checks `CLAUDE_CODE_REMOTE`. Claude Code sets it to `true` only in cloud sessions. On any other value, the hook exits at once and changes nothing, so a session on your Mac is not affected.
2. The hook runs [`scripts/cloud-setup.sh`](../../scripts/cloud-setup.sh). The script installs each missing tool and leaves present tools alone:
   - OpenJDK 25 from the Ubuntu archive with `apt-get`. `apt` verifies the package signatures. Any Java 25 already in `JAVA_HOME` or `/usr/lib/jvm/java-25-openjdk-*` counts as present.
   - sbt 1.11.7: the launcher jar and the runner script, checked against SHA-256 values pinned in the script.
   - Node 24.22.0 from nodejs.org, checked against the release's `SHASUMS256.txt`.
   - pnpm 10.34.4 through Node's Corepack, which checks the package against the npm registry's signing keys.
   - Three small sbt configuration files, described in [Maven Central and the mirror](#maven-central-and-the-mirror).

   As root, the script installs into `/opt/h34s-tools`. As another user, it installs into `~/.local/share/h34s-tools` and uses `sudo -n` for `apt-get`. Set `H34S_TOOLS_DIR` to choose another directory. Its path may contain only letters, digits, and `. _ / + -`, because the sbt runner evaluates it.
3. The hook appends four lines to the file in `CLAUDE_ENV_FILE`: `JAVA_HOME`; a `PATH` line that puts the Java 25, Node 24, and sbt directories in front of the session's `PATH`; `COREPACK_ENABLE_DOWNLOAD_PROMPT=0`; and `COURSIER_REPOSITORIES` for scalafix (see [What the setup changes](#what-the-setup-changes)). Claude Code runs that file before every Bash command, so `java`, `sbt`, `node`, and `pnpm` resolve to the right versions. The hook skips lines that the file already has.
4. The hook prints one line of context for Claude: the toolchain is ready, and sbt and scalafix read Maven Central through a mirror.

On a fresh VM, the hook takes about 15 seconds. When every tool is present, it takes less than half a second and changes no file. If the setup fails, the hook still exits with status 0 and writes no variables, so the session starts without the toolchain. Its JSON output carries a warning for you, `h34s cloud setup failed`, and a note that tells Claude to rerun the script.

### Add the optional setup script

A cloud environment can also run a setup script before Claude Code starts. Anthropic caches the VM's files after a successful setup script, so later sessions start with the tools on disk and the hook has nothing to install. This saves about 15 seconds for each new session.

To use it, open the environment's settings and enter this **Setup script**:

```bash
#!/bin/bash
bash /home/user/h34s/scripts/cloud-setup.sh || true
```

The path is where the verification session found the clone. `|| true` keeps a failed setup from blocking the session; the hook then installs the tools instead.

This setup-script path is not verified. In particular, it is not known whether the clone exists when the setup script runs. If the script is not found, the session simply does not get the cache benefit.

### Debug the hook

Run the setup script yourself. It prints each step to stderr. It ends with a `ready:` line, or with an `error:` line that names the check or the command that failed:

```sh
bash scripts/cloud-setup.sh
```

To see the result of the hook, check which tools the session's commands find:

```sh
command -v java sbt node pnpm
java -version
```

Expected result: `java` is in `/usr/lib/jvm/java-25-openjdk-amd64/bin`, and `sbt`, `node`, and `pnpm` are in `/opt/h34s-tools`. If they are not, the hook did not run or failed. A session with several repositories runs no repository hooks; there, start each command with `eval "$(bash scripts/cloud-setup.sh --print-env)" &&`. Otherwise, run the hook directly with the variables that Claude Code supplies:

```sh
CLAUDE_CODE_REMOTE=true CLAUDE_PROJECT_DIR="$PWD" CLAUDE_ENV_FILE=/tmp/h34s-env \
  .claude/hooks/session-start.sh
cat /tmp/h34s-env
```

## Maven Central and the mirror

### Why sbt needs a mirror here

Most outbound HTTPS traffic from a cloud session, including every Maven download, goes through a local agent proxy. Claude Code sets `JAVA_TOOL_OPTIONS` so that every JVM uses it: `-Dhttps.proxyHost=127.0.0.1`, the proxy's port, and a trust store that holds the proxy's certificate. That is why each JVM prints `Picked up JAVA_TOOL_OPTIONS` on startup. Do not change that variable. The proxy needs no credentials from Java, so older workarounds that run your own forwarding proxy for the JVM ([claude-code#16222](https://github.com/anthropics/claude-code/issues/16222)) are not needed in this environment.

The problem is Maven Central itself. It answers the shared egress address of cloud sessions with `HTTP 429 Too Many Requests` for a large share of requests, at both `repo1.maven.org` and `repo.maven.apache.org`. Without the mirror, a first `sbt -batch h3Ext/compile` failed after 20 seconds:

```text
[error] lmcoursier.internal.shaded.coursier.error.FetchError$DownloadingArtifacts: Error fetching artifacts:
[error] https://repo1.maven.org/maven2/org/portable-scala/sbt-platform-deps_2.12_1.0/1.0.2/sbt-platform-deps_2.12_1.0-1.0.2.jar: download error: Caught java.io.IOException (Server returned HTTP response code: 429 for URL: ...)
```

In other attempts, the sbt launcher failed earlier with `could not retrieve sbt 1.11.7`. Two lighter fixes did not help. coursier does not retry a 429, even with its retry properties raised, and sbt's launcher and build both ignore `COURSIER_MIRRORS`. Google's mirror of Maven Central answered every request in the same tests.

### What the setup changes

`scripts/cloud-setup.sh` writes three files and one variable. Only the sbt in `/opt/h34s-tools` reads the files; an sbt on your own machine never does.

| File | Effect |
| --- | --- |
| `/opt/h34s-tools/sbt-1.11.7/conf/sbtopts` | The sbt runner reads this file when no `/etc/sbt/sbtopts` or `~/.config/sbt/sbtopts` exists. It adds `-Dsbt.repository.config` and `-Dsbt.global.base`, which point at the next two files. |
| `/opt/h34s-tools/sbt-mirror/repositories` | The launcher downloads sbt 1.11.7 and Scala from the local Ivy repository and the mirror only. |
| `/opt/h34s-tools/sbt-mirror/global/maven-central-mirror.sbt` | In every build, including the `project/` meta-build, these global settings replace a Maven Central resolver with the mirror in `csrResolvers`, `csrSbtResolvers`, and `scalaCompilerBridgeScope / csrResolvers`. sbt defines `csrResolvers` a second time for compiler bridges, from the unchanged `fullResolvers`, so the project-level rewrite does not reach it. In the `project/` meta-build, a plugin build, that list includes Maven Central through `sbtResolvers`. |
| `COURSIER_REPOSITORIES`, in `CLAUDE_ENV_FILE` | sbt-scalafix fetches `scalafix-cli` and its rules with its own coursier-interface, not through sbt's resolvers. That library ignores `COURSIER_MIRRORS` too, but reads this variable. The value `ivy2Local|<mirror>` replaces its default, `ivy2Local|central`. Without it, `sbt 'h3Ext/scalafix --check'` failed with 15 HTTP 429 responses. A shell that does not run the hook's environment file does not have this variable. |

The build's other resolvers stay in place, such as GitHub Packages or the staging repository of `scripts/check-consumers.mjs`. That is why the setup does not use `-Dsbt.override.build.repos=true`: that option replaces every resolver of a build, and `check-consumers.mjs` refuses to run with it.

If `$SBT_ETC_FILE`, `/etc/sbt/sbtopts`, or `~/.config/sbt/sbtopts` exists, the runner ignores `conf/sbtopts`, and sbt does not use the mirror. The setup script then prints a warning that names the file. Remove the file to use the mirror again.

Because the global base directory moves to `/opt/h34s-tools/sbt-mirror/global`, sbt keeps its launcher files (`boot/`) and compiled compiler bridges (`zinc/`) there, not in `~/.sbt`.

sbt also keeps two Ivy repositories as fallbacks, `repo.scala-sbt.org` and `repo.typesafe.com`. coursier asks them only for a module that the earlier resolvers do not have. Every h34s dependency is on Maven Central, so the builds never reach them. They are not in the Trusted list; a dependency that exists only there would fail with Trusted access.

### Debug downloads

- To check that downloads use the mirror, list the hosts in coursier's cache. Expected result: `maven-central.storage-download.googleapis.com`, and no `repo1.maven.org` or `repo.maven.apache.org`.

  ```sh
  ls ~/.cache/coursier/v1/https/
  ```

  If a Maven Central host appears, some resolution path missed the rewrite. To find it, empty every cache that hides downloads, run a compiling task with `-debug`, and search the log. The `downloaded` line names the file, and the lines before it name the task. This is how the compiler-bridge scope was found:

  ```sh
  rm -rf ~/.cache/coursier /opt/h34s-tools/sbt-mirror/global/boot /opt/h34s-tools/sbt-mirror/global/zinc project/target project/project/target
  sbt -debug -batch h3Ext/compile > /tmp/sbt-debug.log 2>&1
  grep -n -B5 -E 'repo1\.maven\.org|repo\.maven\.apache\.org' /tmp/sbt-debug.log
  ```

  Expected result: no output. The run takes a few minutes, so start it in the background. sbt-scalafix uses another path; check it with `sbt 'h3Ext/scalafix --check'`.
- To see the options that the runner passes to Java, add `-v`: `sbt -v -batch about`. The runner prints `# Executing command line:` and then one argument per line, including the two `-D` options from `conf/sbtopts`.
- To see recent proxy failures, such as a denied host, read the proxy's status:

  ```sh
  curl -sS "$HTTPS_PROXY/__agentproxy/status"
  ```

  `recentRelayFailures` names the host and the reason.
- Google's mirror can lag behind Maven Central. If a dependency that was released very recently is not found, wait, or test that version on your own machine first.

### Update a pinned version

The script pins sbt 1.11.7, Node 24.22.0, and pnpm 10.34.4. Keep them in step with `project/build.properties`, `mise.toml`, `package.json`, and `.github/workflows/scala.yml`.

To move to another sbt version, compute the two new SHA-256 values and compare them with what upstream publishes. Maven Central publishes SHA-1 and MD5 checksums and a PGP signature for the launcher, but no SHA-256, and the runner script has no published checksum. The release tarball on GitHub, `sbt-<version>.tgz`, has a SHA-256. Its `bin/sbt-launch.jar` must have the same SHA-256 as the Maven Central jar. For 1.11.7, its `bin/sbt` differed from the raw script only in the line `init_sbt_version=_to_be_replaced`.

Run these commands in an empty directory. The first output line is Maven Central's SHA-1; the second must match it.

```sh
cd "$(mktemp -d)"
v=1.11.7
mirror=https://maven-central.storage-download.googleapis.com/maven2
curl -fsSLO "$mirror/org/scala-sbt/sbt-launch/$v/sbt-launch-$v.jar"
curl -fsSL --retry 5 "https://repo1.maven.org/maven2/org/scala-sbt/sbt-launch/$v/sbt-launch-$v.jar.sha1"; echo
sha1sum "sbt-launch-$v.jar"
curl -fsSL -o sbt "https://raw.githubusercontent.com/sbt/sbt/v$v/sbt"
sha256sum "sbt-launch-$v.jar" sbt
```

Then set `sbt_version`, `sbt_launcher_sha256`, and `sbt_runner_sha256` in `scripts/cloud-setup.sh` to the new values, and run `bash scripts/cloud-setup.sh`. Expected result: an `installing sbt` line, then a `ready:` line.

For Node, change `node_version`. The script reads the checksum from that release's `SHASUMS256.txt`.

## Start PostgreSQL and Redis

PostgreSQL 16 and Redis 7 are installed in the VM, but they are stopped. Start both with the setup script:

```sh
bash scripts/cloud-setup.sh --services
```

Or start them yourself:

```sh
service postgresql start
service redis-server start
```

Redis's start script prints `ulimit: error setting limit (Operation not permitted)`. The VM does not allow that limit change; Redis starts anyway, and `redis-cli ping` answers `PONG`.

A running service does not survive a new session or a reclaimed VM. Start it again in each session that needs it.

To connect to PostgreSQL as its administrator:

```sh
runuser -u postgres -- psql -c 'select version();'
```

The [example's database settings](../development.md#2-run-the-model-and-database-example) expect user `h34s` and database `h34s` on `127.0.0.1:5432`. Create them with a random password. Each of Claude's Bash commands starts a new shell, so keep the password in a file outside the repository, not in an exported variable:

```sh
(umask 077 && openssl rand -hex 16 > ~/.h34s-db-password)
runuser -u postgres -- psql -v ON_ERROR_STOP=1 \
  -c "create role h34s login password '$(cat ~/.h34s-db-password)';" \
  -c 'create database h34s owner h34s;'
```

Then apply the example schema. Compose does this for its own database; the VM's PostgreSQL does not:

```sh
PGPASSWORD="$(cat ~/.h34s-db-password)" psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U h34s -d h34s \
  -f examples/nuxt/scala/zio/src/main/resources/sql/01-init.sql
```

Expected result: `CREATE TABLE`. Give the password to Nuxt the same way, with `NUXT_DATABASE_PASSWORD="$(cat ~/.h34s-db-password)"`. Do not copy the password into a file in the repository. Docker is also available if you prefer the example's Compose file, but this guide has not tested it in a cloud session.

## Work within the session's limits

A cloud VM has about 4 vCPUs, 16 GB of memory, and 30 GB of disk. h34s builds are heavy for that size.

- Run one sbt process at a time. `.jvmopts` gives each sbt JVM up to 4 GiB of heap, and two builds in the same checkout also compete for the same `target/` directories.
- Run any command that can take more than a few minutes in the background, and wait for it to finish. Claude waits at most 10 minutes for a foreground command; after that, Claude Code moves the command to the background, where it can run for about 30 more minutes. On this VM, a first `sbt -batch h3Ext/test kyoH3Ext/test` with empty caches took about two minutes. The rest of the Scala CI job took about 6 more minutes after that run; on GitHub's hosted runner, the whole job takes 8 to 12 minutes.
- Do not run the example's Nuxt production build while sbt runs. That build uses an 8 GiB Node heap.

## What does not work in cloud sessions

- **Tags and releases.** The GitHub proxy accepts pushes of branches only. A tag push fails, so the release workflow, which starts from a tag, cannot start from a cloud session. Make releases from your own machine, as [releases.md](../releases.md) describes.
- **Branch deletion.** The GitHub proxy also rejects pushes that delete a branch. Delete branches on GitHub instead.
- **`gh pr` and `gh issue`.** These commands use GitHub's GraphQL API, which the proxy rejects with a 403. Use the REST API through `gh api`, or the **Create PR** button in the Claude Code interface. For example:

  ```sh
  gh api repos/yuri4n/h34s/pulls -X POST -f title='Title' -f head=my-branch -f base=main -F draft=true -F body=@body.md
  gh api 'repos/yuri4n/h34s/issues?state=open'
  gh api repos/yuri4n/h34s/issues/<number>/comments -f body='Comment text'
  ```

- **Private GitHub Packages.** Only the downstream `yurian.land` application and `node scripts/check-consumers.mjs --published` need to read the private `maven.pkg.github.com/yuri4n/h34s` registry. This is not tested in a cloud session. In the session, `GITHUB_TOKEN` holds the placeholder `proxy-injected`, and it is not known whether the GitHub proxy supplies real credentials for Maven requests to `maven.pkg.github.com`.
- **Personal skills.** Skills from your own `~/.claude`, such as `functional-design` and `scala-functional-design`, are not part of this repository. A cloud session loads them only when you enable them in your claude.ai settings.

## Verification record

On 2026-10-19, in a cloud session with **Full** network access (Ubuntu 24.04.5, x86_64, 4 vCPUs, 15 GiB memory), starting from a VM with only Java 21 and Node 22:

- The hook on a fresh VM installed OpenJDK 25.0.4.1, sbt 1.11.7, Node 24.22.0, and pnpm 10.34.4 in 13 to 17 seconds (16.7 seconds with the final script). Later runs took about 0.35 seconds each, changed no file, and kept the environment file at four lines. With `CLAUDE_CODE_REMOTE` unset or `false`, the hook wrote nothing. When the setup failed, the hook exited with status 0, wrote no variables, and printed its JSON warning.
- With only the hook's environment and empty sbt and coursier caches, `sbt -batch h3Ext/test kyoH3Ext/test` passed in 1 minute 50 seconds: `h3Ext` 36 tests and `kyoH3Ext` 79 tests. coursier's cache then held one host, the mirror. A fully cold `sbt -debug -batch about` requested nothing from `repo1.maven.org` or `repo.maven.apache.org`.
- `pnpm install --frozen-lockfile --ignore-scripts` passed in 5 seconds, and `node --test scripts/*.test.mjs packages/nuxt/test/*.test.mjs` passed 47 tests.
- Right after that, every step of the Scala CI job in `.github/workflows/scala.yml` passed, in order, in about 6 minutes: the ten sbt test and compile tasks (154 seconds; the ZIO suites reported no failures), `pnpm run scala:full` (37 seconds), `sbt -batch releaseCheck`, `node scripts/check-release.mjs --allow-snapshot`, `sbt -batch releasePublishLocal`, and `node scripts/check-consumers.mjs` (132 seconds; the zio, kyo, zio-schema, and kyo-schema consumers served real h3 requests through their explicit Maven Central resolver, rewritten to the mirror).
- `bash scripts/cloud-setup.sh --services` started PostgreSQL 16.15 and Redis, also when its output went through a pipe. The example role, database, and schema commands above worked.
- After the review fixes, with `COURSIER_REPOSITORIES` set and an empty coursier cache: `sbt -batch h3Ext/test kyoH3Ext/test` passed again (36 and 79 tests); `sbt 'h3Ext/scalafix --check'` fetched scalafix-cli from the mirror only and then stopped with `NoRulesError`, because `.scalafix.conf` enables no rule; and `node scripts/check-consumers.mjs` passed for all four consumers. coursier's cache held only the mirror host.

Not verified: the hook firing by itself when a session starts (this session began before the hook existed, so it ran with the variables that Claude Code documents), the Trusted access level, the cached setup script, a non-root user, arm64, private GitHub Packages, the example's Nuxt build, Docker Compose, and Nix.
