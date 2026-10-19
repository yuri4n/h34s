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
| The ZIO demo against a real PostgreSQL | Not run on this branch (no database was available) |
| The Nitro close hook when `nuxt dev` replaces its worker | Not checked; `SIGTERM` in production was |
| kyo-sql / kyo-sql-postgres at runtime, inside Nitro, including `KYO_NET_BACKEND=node` | Not run. The [database guide](database.md) code compiles only |
| NuxtHub KV as a Nitro storage mount | Not verified; storage tests use Unstorage 1.17.3's memory driver |
| Nitro presets other than Node (serverless, edge, Workers) | Not tested; streaming needs a Node HTTP response |
| Linux beyond the CI checks above, Windows | Not run |
| Nix / NixOS | No Nix profile exists yet |
| Other Scala, Kyo, ZIO or h3 versions | Not supported; Kyo 1.0.0-RC7 needs Scala 3.9.0 and JDK 25 |

### Known gaps

- **ZIO YAML on Scala.js.** zio-json-yaml is published for the JVM only (no `_sjs1_3` artifact on Maven Central), so a ZIO model has no YAML codec in Nitro. Kyo models get YAML from kyo-schema-yaml.
- **ZIO Schema shapes.** The ZIO bridge supports strings, booleans, `Int`, `Double`, records, options, sequences, lazy and transformed schemas. Enums, sealed traits and maps are not supported yet. See [schemas](schema.md).
<!-- claude 2026-10-19: changed: ZIO follows Kyo on finalizer failures (issue #40) -->
- **Finalizer failures** are logged and do not change the response, in Kyo and in ZIO. Before `0.3.0-alpha.1`, a dying ZIO finalizer turned a value response into a 500. See [lifetimes](lifetimes.md).
- **Build memory.** The demo links both a Kyo and a ZIO application; its `nuxt build` needed `NODE_OPTIONS=--max-old-space-size=8192`. A single-runtime application links less code, but check your own build.

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
