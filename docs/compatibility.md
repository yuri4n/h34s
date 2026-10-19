# Compatibility matrix

A compatibility profile is a set of versions that was tested together, on a stated platform, with stated evidence. This page keeps three kinds of evidence apart:

- **Locally verified:** run by hand or by an agent on one machine. Useful, but not repeatable by others yet.
- **CI-validated:** run by a GitHub Actions workflow, with a link to the run.
- **Untested:** configured or expected to work, with no evidence. Do not rely on it.

Listing a library here does not make it a dependency of your application. Add only the modules you use.

## Profile `alpha-0.2.0` (`0.2.0-alpha.1`, published)

Version `0.2.0-alpha.1`, with the Kyo core and the ZIO adapter. It was published on 2026-10-18 from commit `433ac9d`, the merge of [#14](https://github.com/yuri4n/h34s/pull/14): the [Maven prerelease](https://github.com/yuri4n/h34s/releases/tag/v0.2.0-alpha.1) and the [npm release](https://github.com/yuri4n/h34s/releases/tag/npm-v0.2.0-alpha.1). The [release evidence](#release-workflows) below links the workflows that published and verified the packages.

| Component | Version |
| --- | --- |
| Artifacts | nine `dev.yuri4n` modules, `0.2.0-alpha.1`, private GitHub Packages |
| Scala (every module) | 3.9.0 |
| Scala.js linker | 1.22.0 |
| Build JDK | 25 (checked with Temurin 25.0.4.1+1) |
| sbt | 1.11.7 |
| Kyo | 1.0.0-RC7 (kyo-core, kyo-zio, kyo-schema; kyo-schema-json and kyo-schema-yaml in tests) |
| ZIO | 2.1.26; zio-prelude 1.0.0-RC41; zio-json 0.7.44; zio-schema 1.7.5 |
| NativeConverter | 0.9.0 |
| h3 | 1.15.4 (tests and the demo's runtime dependency) |
| Skunk (demo only) | 1.0.0-M12 |
| Node / pnpm | 24 (checked with 24.19.0) / 10.34.4 |
| Nuxt (demo lockfile) | 4.3.1, with Nitro 2.13.1 |
| npm tooling | `@yuri4n/h34s-nuxt@0.2.0-alpha.1`, private GitHub Packages (the repository demo uses the workspace link) |

### Locally verified

On macOS 15.7.5, x86_64, during the refactor (2026-10-17):

| Check | Result |
| --- | --- |
| `h3Ext/test` | 36 passed |
| `kyoH3Ext/test` | 79 passed |
| `kyoNuxtHubExt/test` | 15 passed |
| `nativeConverterKyoSchemaExt/test` | 25 passed |
| `zioH3Ext/test` | 81 passed |
| `zioNuxtHubExt/test` | 14 passed |
| `nativeConverterZIOSchemaExt/test` | 20 passed |
| `nuxtsvr/test` (ZIO demo) | 6 passed |
| `nativeConverterZIOJsonExt`, `zioMigrationsExt` | compile |
| `kyoExample/fastLinkJS`, `nuxtsvr/fastLinkJS` | link |
| `releaseCheck` (POMs for the 9 release modules) | passed |
| Node tests: `scripts/*.test.mjs`, `packages/nuxt/test/*.test.mjs` | All 47 passed (after the Phase D review fixes) |
| Documentation snippets that do not come from compiled sources (Kyo and ZIO guides, configuration, lifetimes, storage, streaming, testing, adoption, and the kyo-sql code with kyo-sql-postgres 1.0.0-RC7) | Compiled in a temporary test file against this branch (`kyoNuxtHubExt/Test/compile`, `zioNuxtHubExt/Test/compile`); not run |
| Fresh Maven consumers (`scripts/check-consumers.mjs`) for Kyo, ZIO, Kyo Schema and ZIO Schema, from a temporary Maven repository | Compiled, linked and served real h3 requests with the new wire format; the Kyo consumers resolved no `dev.zio` artifact |
| Kyo demo over a plain h3 server with a preemptive router (Nitro's router mode) | GET and POST 200 with `{"data":...}`; 422 entries with `path` for a blank or 81-character name; generic 422 for a wrong shape; 400 for invalid JSON; `Cache-Control: no-store` on every response |
| ZIO demo over the same h3 server | `/api/test` answers `abc`; `/api/notes` without the plugin is an opaque 500; with a fake plugin record and no database, a 500 with `debug.cause` and no password in it, and one close hook registered and released |
| Production build of the demo (Scala full link, `nuxt build`, then the Node server from `.output/`) | Built with `NODE_OPTIONS=--max-old-space-size=8192` (the default heap ran out of memory). Kyo routes: the same 200, 422, 400 responses, and 415 for `text/plain`. `/api/test` streamed `abc`. `/api/notes` without a database: opaque 500. With `NUXT_H34S_DEBUG=true`: `debug.cause` present, password absent. `SIGTERM`: the `ZShared` pool registered on the first Notes request was released, exit status 0 |
| Production build after the review fixes (same machine, same day) | `kyo-smoke.mjs` passed with the default salutation and with `NUXT_GREETING_SALUTATION=Hi` (Kyo `RuntimeConfig.load` in a `Shared`); `smoke.mjs` with `H34S_SMOKE_SKIP_DATABASE=true` passed without PostgreSQL (invalid Notes input is a 422 before any database work); streams carry `text/plain; charset=utf-8` and `nosniff` by default |
| `nuxt dev` on the demo (Nuxt 4.3.1, `pnpm dev` after `pnpm run scala:fast`) | Kyo GET 200 with `{"data":...}` and `no-store`; a blank name 422 with path `name`; `/api/test` answered `abc`; `/api/notes` without a database answered a 500 with `debug.cause` (Scala stack frames, no password). A Kyo handler that threw a JavaScript `TypeError` (a temporary probe route) had its stack mapped to `RequestLifecycle.scala` and `KHandler.scala` |
| `scripts/check-npm-nuxt.mjs` (the packed module in a synthetic Nuxt 4.3.1 project) | Development diagnostics, direct loading, Scala source maps, a relink reload, production bundling, and `NUXT_H34S_DEBUG=true` at runtime |

The Scala tests use real h3 1.15.4 events with mocked Node responses. They do not start Nuxt or Nitro. The consumer and production-build checks come from Phase C1 of the refactor, on the same machine.

<!-- claude 2026-10-19: created: cloud-session evidence for issues #29 and #30 -->
### Locally verified in a cloud session (2026-10-19)

In a Claude Code cloud session: Ubuntu 24.04.5, x86_64, 4 vCPUs, 15.7 GiB memory, Node 24.22.0, pnpm 10.34.4, OpenJDK 25.0.4.1, sbt 1.11.7, Nuxt 4.3.1, Nitro 2.13.1, Nuxt Content 3.11.2, and PostgreSQL 16.15 (`psql (PostgreSQL) 16.15 (Ubuntu 16.15-0ubuntu0.24.04.1)`), from commit `f1fb8e1`; the kyo-sql row with `KyoSqlPostgresSuite` from commit `314546f` (the first four tests also from `70688b2`). The [development guide](development.md#cloud-session-production-build-and-postgresql-2026-10-19) has the full record.

| Check | Result |
| --- | --- |
| Production build of the demo (`pnpm run scala:full`, then `nuxt build` with `--max-old-space-size=8192`) | Passed; 2:14 for `pnpm run scala:full` (cold sbt start, compile and link), 2:15 for the Nuxt build, 6.5 GiB peak RSS |
| ZIO Notes against PostgreSQL 16 with `scram-sha-256` (production server, `.env.example` variables) | `smoke.mjs` passed with the database. `POST /api/notes` 201; blank text 422 with path `text`; `{"text":42}` the generic 422; `GET /api/notes` 200 with the note |
| Skunk 1.0.0-M12 SCRAM login in the production bundle | PostgreSQL logged `connection authenticated: identity="h34s" method=scram-sha-256` |
| `SIGTERM` with 4 pooled sessions open | Exit status 0; 4 disconnections logged; 0 sessions of the user in `pg_stat_activity` |
| kyo-sql code of the [database guide](database.md#run-the-kyo-sql-tests), kyo-sql-postgres 1.0.0-RC7, in Node through `sbt kyoExample/test` with `H34S_POSTGRES_URL` | 5 passed, twice: insert and list, unique violation to `DuplicateNote`, rollback on a non-SQL `Abort`, rollback (with `SqlRequestTransactionFailedStatementException`) when the body recovers from a failed statement, and a failed nested transaction that rolls back only its own part. The nested test also checks that the nested step failed with SQLSTATE 23505. After each run, no suite row was left in `notes`. kyo-net selected its `node` backend by itself. Without the variable, the 5 tests are skipped; with `H34S_POSTGRES_REQUIRED=true` and no variable, they fail |
| `pnpm run test:package` with the `scalaPrerender: 'stub'` checks (commit `ae4c502`) | Passed. `check-npm-nuxt.mjs` set the option in the packed consumer. A build that also prerendered a route calling a Scala.js route through `event.$fetch`, and catching the 501, failed with the `[h34s]` warning and the list of requests. The build that prerendered only a route without Scala.js passed: its prerender bundle had the stub and no linked code, its server output had the linked code and no stub, and the production server ran a directly mounted linked handler. Without the `prerender:done` report or without the stub plugin, the check failed <!-- claude 2026-10-19: created: the real-build check (review F1, F2) --> |
| `pnpm run test:package` after the review fixes (commit `68f6100`) | Passed in 44 s. The build that prerendered the route calling a Scala.js route failed with the `[h34s]` warning and the list, and its log had no `[request error]` or `[unhandled]` line. A third build also prerendered the Scala.js route itself: it failed with Nitro's `Errors prerendering:` summary and `Exiting due to prerender errors.`, h34s logged the list of both requests, and the log had no unhandled-error line <!-- claude 2026-10-19: created: the real-build check of the review fixes (review F1, F2) --> |

#### Heap measurement (2026-10-19)

Each row is one `nuxt build` of the demo, run alone, on the production link above. A variant is a temporary change that was reverted afterwards, except the last three rows: the module option `scalaPrerender: 'stub'`, which the demo now uses. The 4096 MiB limit stands for Node's default heap on a 16 GB Mac (about 4 GiB). On this VM, Node's own default limit is 2240 MiB (`env -u NODE_OPTIONS node -e 'console.log(require("v8").getHeapStatistics().heap_size_limit/1048576)'`). The session's environment set `NODE_OPTIONS=--max-old-space-size=8192`; a command-line `--max-old-space-size` overrides it for the build process. In a full 8192 MiB build sampled every 10 seconds (14 samples), the only child processes were two esbuild services, which have no Node heap, so the limits in the table apply to the build's only Node process. Peak RSS is the largest process, from GNU `time -v` (KiB), given in GiB. <!-- claude 2026-10-19: created: the scalaPrerender rows (review F3); three module option rows -->

| Variant | Heap limit | Result | Peak RSS | Wall time |
| --- | --- | --- | --- | --- |
| Baseline (both applications, server source maps, prerendering) | 8192 MiB (the scripts' option) | Passed | 6.50 GiB | 2:15 |
| Baseline | Node's real default on this VM (2240 MiB; `NODE_OPTIONS` removed) | Out of memory while it built the prerender bundle ("Initializing prerenderer") | 2.58 GiB | 1:00 |
| Baseline | 6144 MiB | Passed | 5.46 GiB | 2:06 |
| Baseline | 4096 MiB | Out of memory in "Building Nuxt Nitro server", after prerendering | 4.66 GiB | 2:36 |
| No server source maps (`sourcemap.server=false` in `.nuxtrc`) | 4096 MiB | Passed; no `.map` file in `.output/server` | 4.34 GiB | 1:56 |
| No prerendering (a temporary `prerender:routes` hook that clears the one route) | 4096 MiB | Passed | 4.02 GiB | 1:16 |
| Kyo application only (the ZIO route files moved away) | 4096 MiB | Passed; prerendering took 17 s | 3.41 GiB | 1:01 |
| ZIO application only (the Kyo route files moved away) | 4096 MiB | Passed; prerendering took 33 s | 4.46 GiB | 1:46 |
| No full locale database (`locales-full-currencies-db` excluded from `nuxtsvr` too) | Not built | The database jar is on the ZIO application's classpath (`show nuxtsvr/Compile/fullClasspath`), but excluding it did not change the linked output (same name, size and time stamp), so the build input does not change | Not measured | Not measured |
| Baseline, a second run on the same day | 8192 MiB | Passed; prerendering took 54 s | 6.42 GiB | 2:13 <!-- claude 2026-10-19: created: baseline for the stub rows --> |
| Stub through a temporary `nuxt.config` hook that added the same Rollup plugin to the prerender build, before the option existed | 4096 MiB | Passed; prerendering took 5.7 s. The plugin replaced exactly the 5 Scala.js route entries (`kyo-greeting-get`, `kyo-greeting-post`, `notes-get`, `notes-post`, `test`). The prerendered `sql_dump.txt` had the baseline's SHA-256 (`15069d1d1d010d84d8b3f879a12c2efaf4335d6e5feb5c154127f0df1574573d`), and the stub's text was not in `.output/server`. The `postgres` CI job's serve step, run on this VM (`smoke.mjs` with PostgreSQL, 4 sessions before `SIGTERM`, exit status 0, 0 after), passed on this build | 4.13 GiB | 1:41 <!-- claude 2026-10-19: created: stub measurement --> |
| The same hook | Node's real default on this VM (2240 MiB; `NODE_OPTIONS` removed) | Prerendering passed (3.5 s), then out of memory in "Building Nuxt Nitro server" | 2.58 GiB | 0:43 <!-- claude 2026-10-19: created: stub at the default heap --> |
| `scalaPrerender: 'stub'`, the module option (commit `cca91f5`) | 4096 MiB | Passed; prerendering took 3.8 s. `sql_dump.txt` had the same SHA-256. The same serve step passed on this build. The contents of the prerender bundle and of `.output/server` were not recorded for this build | 4.05 GiB | 1:23 <!-- claude 2026-10-19: created: no kyo-smoke.mjs claim without a log (review F3); no bundle claims without a record (review F7) --> |
| `scalaPrerender: 'stub'`, the module option with the request report (commit `ae4c502`) | 4096 MiB | Passed; prerendering took 3.6 s, and no request reached the stub. The prerender bundle (90 files, 1,207,913 bytes, about 1.2 MB; `du -sh` reports 1.5M of disk use) mounts the stub for all 5 Scala.js routes; `.output/server` bundles the Scala.js and has no stub text. `sql_dump.txt` had the same SHA-256. The same serve step passed, and `kyo-smoke.mjs` passed against the same server build, which then exited with status 0 on `SIGTERM` | 4.07 GiB | 1:18 <!-- claude 2026-10-19: created: the final module option build (review F3); the bundle's file size, not its disk use (review F7) --> |
| `scalaPrerender: 'stub'`, the stub's 501 as a handled h3 error (commit `68f6100`) | 4096 MiB | Passed; prerendering took 3.9 s, and no request reached the stub (no `[h34s]`, `[request error]` or `[unhandled]` line). The prerender bundle (90 files, 1,208,871 bytes, about 1.2 MB) mounts the stub for all 5 Scala.js routes and has no Scala.js classes; `.output/server` bundles the Scala.js and has no stub text. `sql_dump.txt` had the same SHA-256. This build was not served | 4.06 GiB | 1:18 <!-- claude 2026-10-19: created: the build after the review fixes (review F7) --> |

Cause: Nitro 2.13.1 bundles the linked Scala.js (16.5 MB of JavaScript and 13.2 MB of source maps) twice in one process. First it builds a complete `nitro-prerender` server for the one prerendered Nuxt Content route and imports it, which took 47 to 59 s across the baseline runs. Then it builds the `node-server` output. Removing the prerendered route, the server source maps, or one application each let the build pass with a 4096 MiB heap limit; peak RSS was then 3.4 to 4.5 GiB. The measurement does not separate how much memory each Rollup build keeps. A first attempt at the source-map row appended to `.nuxtrc` without a newline, so Nuxt ignored the setting; those two runs are not in the table. The module option `scalaPrerender: 'stub'` keeps the Scala.js out of the first bundle. With it, the build passed with a 4096 MiB heap limit. With the equivalent temporary hook, it did not pass with 2240 MiB: the stub removes the duplicate bundle, not the need for more than about 2.2 GiB. <!-- claude 2026-10-19: created: what the stub removes; the 2240 MiB run used the temporary hook (review F3) -->

### CI-validated

Pull request CI for [#14](https://github.com/yuri4n/h34s/pull/14) passed on GitHub's `ubuntu-24.04` runners (Linux x86_64) for commit `8f9e222`, with version `0.2.0-SNAPSHOT`:

- The [Scala.js libraries workflow](https://github.com/yuri4n/h34s/actions/runs/39075718558): the Node tests, every Scala test suite, production linking of both demo applications, `releaseCheck`, `check-release.mjs --allow-snapshot`, and the four fresh Maven consumers.
- The [Nuxt build tooling workflow](https://github.com/yuri4n/h34s/actions/runs/39075718554): package tests and the packed-archive check on Node 22.18.0 and 24, and the Nuxt 4.3.1 development and production HTTP checks.
- The [documentation website workflow](https://github.com/yuri4n/h34s/actions/runs/39075718873).

The same workflows passed again on the merge commit `433ac9d` on `main`, with version `0.2.0-alpha.1`: [Scala.js libraries](https://github.com/yuri4n/h34s/actions/runs/39144782174), [Nuxt build tooling](https://github.com/yuri4n/h34s/actions/runs/39144782180) and the [documentation website](https://github.com/yuri4n/h34s/actions/runs/39144782493).

#### Release workflows

Both tags point at `433ac9d`. The workflows ran on GitHub's Linux x86_64 runners, and the npm release finished before the Maven release started.

- The [npm release workflow](https://github.com/yuri4n/h34s/actions/runs/39144809255) for `npm-v0.2.0-alpha.1`: package tests and the packed-archive check on Node 22.18.0 and 24, then the packed module in a Nuxt 4.3.1 project with development and production HTTP checks. It published `@yuri4n/h34s-nuxt@0.2.0-alpha.1`, downloaded it again with registry authentication, and required the download to be byte-identical to the tested archive before an offline install checked the CLI and the JavaScript entry points. The [npm release](https://github.com/yuri4n/h34s/releases/tag/npm-v0.2.0-alpha.1) attaches that archive and `registry.json` with its registry location and integrity.
- The [Maven release workflow](https://github.com/yuri4n/h34s/actions/runs/39145732257) for `v0.2.0-alpha.1`: the validation job (development integration, every Scala test suite, production linking, publication metadata, and the four fresh consumers from a staged repository), then publication of the nine artifacts to GitHub Packages. `node scripts/check-consumers.mjs --published` then resolved the Kyo, ZIO, Kyo Schema and ZIO Schema consumers from the authenticated registry, and each one served real h3 requests. The [prerelease](https://github.com/yuri4n/h34s/releases/tag/v0.2.0-alpha.1) was created only after that check passed.

#### Downstream consumer

The private `yurian.land` application consumes the published packages in its pull request [#15](https://github.com/yuri4n/yurian.land/pull/15). It resolves `kyo-h3-ext`, `kyo-nuxthub-ext` and `native-converter-kyo-schema-ext` `0.2.0-alpha.1` from GitHub Packages and installs `@yuri4n/h34s-nuxt@0.2.0-alpha.1` with Nuxt 4.4.2. Its [CI run](https://github.com/yuri4n/yurian.land/actions/runs/39147055600) for commit `0337ccb` passed on GitHub's Ubuntu 24.04 runner: Node and Scala tests, a production Nuxt build, and API checks against PostgreSQL 16 and Redis. This is evidence for one Kyo application, not for the ZIO modules or other Nuxt setups.

### Untested

| Area | Status |
| --- | --- |
| Relinking the real Scala.js demo under `nuxt dev`, and breakpoint stepping | Not checked (the relink reload was checked on a synthetic project) |
| The Nitro close hook when `nuxt dev` replaces its worker | Not checked; `SIGTERM` in production was |
| kyo-sql / kyo-sql-postgres inside Nitro, including `KYO_NET_BACKEND=node` | Not run. The [database guide](database.md) service code ran against PostgreSQL 16 in Node through sbt (see above), not in a Nitro bundle <!-- claude 2026-10-19: changed: kyo-sql ran in Node --> |
| kyo-sql guide code vs `KyoSqlPostgresSuite` | Copied by hand; no check keeps them identical (byte-identical on 2026-10-19). The `postgres` CI job compiles and runs the suite, not the guide <!-- claude 2026-10-19: created: drift risk --> |
| PostgreSQL in CI | The `postgres` job of the Scala.js libraries workflow runs PostgreSQL 16 with SCRAM, the kyo-sql suite (`H34S_POSTGRES_REQUIRED=true`, so it cannot skip), a check that the suite left no rows, the demo's production build, `smoke.mjs` and the `SIGTERM` session check. It runs on pull requests and pushes to `main` that touch the libraries or the example. It skips release tags (`release.yml` reuses this workflow), so it does not gate a release. Its first run on [#45](https://github.com/yuri4n/h34s/pull/45) failed: the `SIGTERM` check counted PostgreSQL's logical replication launcher, which runs as the bootstrap superuser `h34s` in the job's container. The [corrected job](https://github.com/yuri4n/h34s/actions/runs/39277276171/job/118144813255) passed on `042cef3` on GitHub's `ubuntu-24.04` runner: the suite 5 of 5 with 0 rows left, `pnpm build` in 1:43, `smoke.mjs`, and 4 sessions before `SIGTERM`, exit status 0, 0 after. No run on `main` or a release tag yet <!-- claude 2026-10-19: created: CI job --> |
| NuxtHub KV as a Nitro storage mount | Not verified; storage tests use Unstorage 1.17.3's memory driver |
| Nitro presets other than Node (serverless, edge, Workers) | Not tested; streaming needs a Node HTTP response |
| Linux beyond the CI checks above, Windows | Not run |
| Nix / NixOS | No Nix profile exists yet |
| Other Scala, Kyo, ZIO or h3 versions | Not supported; Kyo 1.0.0-RC7 needs Scala 3.9.0 and JDK 25 |

### Known gaps

- **ZIO YAML on Scala.js.** zio-json-yaml is published for the JVM only (no `_sjs1_3` artifact on Maven Central), so a ZIO model has no YAML codec in Nitro. Kyo models get YAML from kyo-schema-yaml.
- **ZIO Schema shapes.** The ZIO bridge supports strings, booleans, `Int`, `Double`, records, options, sequences, lazy and transformed schemas. Enums, sealed traits and maps are not supported yet. See [schemas](schema.md).
- **Kyo finalizer errors** are logged and do not change the response; ZIO finalizer defects turn a value response into a 500. See [lifetimes](lifetimes.md).
- **Build memory.** The demo's `nuxt build` keeps `--max-old-space-size=8192`. Nitro bundles the linked Scala.js twice (once for the prerendered Nuxt Content route, once for the server) with its source maps. Without `scalaPrerender: 'stub'`, a 4096 MiB heap limit runs out of memory, and a 6144 MiB limit passed in one run. The demo now sets `scalaPrerender: 'stub'`: with it, a 4096 MiB limit passed. With an equivalent temporary hook, Node's 2240 MiB default on a 15.7 GiB cloud VM ran out of memory. Without the prerendered route, without server source maps, or with one application, the build also passed with a 4096 MiB heap limit; see the [heap measurement](#heap-measurement-2026-10-19). The scripts keep 8 GiB as a margin. A single-runtime application links less code, but check your own build. <!-- claude 2026-10-19: changed: the measured cause and the scalaPrerender option; the 2240 MiB run used a temporary hook (review F3) -->

## Profile `alpha-0.1.0` (published)

The published `0.1.0-alpha.1` release, with the older API and two independent runtimes. See the [migration guide](migration.md) to move off it.

| Component | Version |
| --- | --- |
| Artifacts | eleven `dev.yuri4n` modules, `0.1.0-alpha.1`, private GitHub Packages |
| Scala | 3.5.2 for native and ZIO modules; 3.9.0 for Kyo modules |
| Scala.js / Kyo / ZIO | 1.22.0 / 1.0.0-RC7 / 2.1.23 |
| Build JDK / sbt | 25 / 1.11.7 |
| npm tooling | `@yuri4n/h34s-nuxt@0.1.0-alpha.1`, Node 22.18 or later, Nuxt 4.3.1 or 4.4.2 |

CI-validated: the [Maven release workflow](https://github.com/yuri4n/h34s/actions/runs/38779659501) checked authenticated ZIO, Kyo and Schema consumers on Linux x86_64. The [npm release workflow](https://github.com/yuri4n/h34s/actions/runs/38937318206) checked the registry archive in an offline consumer. Nuxt 4.3.1 passed the packed-package HTTP checks in CI; Nuxt 4.4.2 passed them locally on Linux x86_64 with Node 24.19.0.

## Nix

No Nix profile exists. When one does, record its lock revision in the profile, run the same sbt/pnpm pipeline inside it, and validate the packaged Node application. A working development shell alone does not establish reproducible builds.

## Add a profile

Add a row only with exact versions, a platform and evidence. Promote a profile to "supported" only after CI runs its compilation, handler tests, optional integrations and consumer setup, and link those runs here.
