# Compatibility matrix

A compatibility profile is a set of versions that was tested together, on a stated platform, with stated evidence. This page keeps three kinds of evidence apart:

- **Locally verified:** run by hand or by an agent on one machine. Useful, but not repeatable by others yet.
- **CI-validated:** run by a GitHub Actions workflow, with a link to the run.
- **Untested:** configured or expected to work, with no evidence. Do not rely on it.

Listing a library here does not make it a dependency of your application. Add only the modules you use.

## Profile `kyo-core-dev` (this branch, unreleased)

The `feat/kyo-core` branch, version `0.2.0-SNAPSHOT`. Nothing in this profile is published.

| Component | Version |
| --- | --- |
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

### Locally verified

On macOS 15.7.5, x86_64, during the refactor (2026-10-17):

| Check | Result |
| --- | --- |
| `h3Ext/test` | 30 passed |
| `kyoH3Ext/test` | 68 passed |
| `kyoNuxtHubExt/test` | 13 passed |
| `nativeConverterKyoSchemaExt/test` | 23 passed |
| `zioH3Ext/test` | 74 passed |
| `zioNuxtHubExt/test` | 13 passed |
| `nativeConverterZIOSchemaExt/test` | 19 passed |
| `nuxtsvr/test` (ZIO demo) | 5 passed |
| `nativeConverterZIOJsonExt`, `zioMigrationsExt` | compile |
| `kyoExample/fastLinkJS`, `nuxtsvr/fastLinkJS` | link |
| `releaseCheck` (POMs for the 9 release modules) | passed |
| Node tests: `scripts/*.test.mjs`, `packages/nuxt/test/*.test.mjs` | 37 passed at the Phase A+/B tip |
| Kyo demo over a plain h3 server with a preemptive router (Nitro's router mode) | GET and POST 200 with `{"data":...}`; 422 entries with `path` for a blank or 81-character name; generic 422 for a wrong shape; 400 for invalid JSON; `Cache-Control: no-store` on every response |
| ZIO demo over the same h3 server | `/api/test` answers `abc`; `/api/notes` without the plugin is an opaque 500; with a fake plugin record and no database, a 500 with `debug.cause` and no password in it, and one close hook registered and released |

The Scala tests use real h3 1.15.4 events with mocked Node responses. They do not start Nuxt or Nitro.

Phase C1 of the refactor (scripts, CI and the demo) adds isolated Maven consumer checks for `0.2.0-SNAPSHOT` (Kyo, ZIO, Kyo Schema, ZIO Schema; Kyo consumers must not resolve `dev.zio`) and real Nuxt build checks. Their results are recorded in [development](development.md) and [releases](releases.md) when that work lands.

### CI-validated

None yet. The branch has not been pushed, so no workflow has run on it. The workflows in `.github/workflows/` describe what CI will run.

### Untested

| Area | Status |
| --- | --- |
| A real `nuxt dev` or production server running this branch | Not recorded here; part of the Phase C verification |
| The ZIO demo against a real PostgreSQL | Not run on this branch |
| `NUXT_H34S_DEBUG=true` and the Nitro close hook inside a real Nitro server | Tested with stubs and fake plugin records only |
| kyo-sql / kyo-sql-postgres inside Nitro, including `KYO_NET_BACKEND=node` | Not run. The [database guide](database.md) is a sketch |
| NuxtHub KV as a Nitro storage mount | Not verified; storage tests use Unstorage 1.17.3's memory driver |
| Nitro presets other than Node (serverless, edge, Workers) | Not tested; streaming needs a Node HTTP response |
| Linux, Windows | Not run on this branch |
| Nix / NixOS | No Nix profile exists yet |
| Other Scala, Kyo, ZIO or h3 versions | Not supported; Kyo 1.0.0-RC7 needs Scala 3.9.0 and JDK 25 |

### Known gaps

- **ZIO YAML on Scala.js.** zio-json-yaml is published for the JVM only (no `_sjs1_3` artifact on Maven Central), so a ZIO model has no YAML codec in Nitro. Kyo models get YAML from kyo-schema-yaml.
- **ZIO Schema shapes.** The ZIO bridge supports strings, booleans, `Int`, `Double`, records, options, sequences, lazy and transformed schemas. Enums, sealed traits and maps are not supported yet. See [schemas](schema.md).
- **Kyo finalizer errors** are logged and do not change the response; ZIO finalizer defects turn a value response into a 500. See [lifetimes](lifetimes.md).

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
