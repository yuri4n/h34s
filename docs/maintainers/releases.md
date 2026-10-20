<!-- claude 2026-10-20: created: the maintainer part of docs/releases.md (issue #25); consumers read the Releases page -->

# Release h34s

This page is for maintainers: it validates, tags and publishes the Maven libraries and the npm build tooling. To install a published version, follow [Releases](../content/2.reference/2.releases.md).

## Validate and publish a release

The Scala workflow checks the development integration, ZIO/Kyo handlers, optional schema and NuxtHub KV adapters, application tests, production linking, and reusable-library POMs. It stages library packages locally and verifies independent Maven consumers before a release can proceed. Its `postgres` job, which builds the demo for production and runs the Notes routes and the kyo-sql suite against PostgreSQL 16, runs on pull requests and on `main`, not on release tags: a release validates the libraries only. <!-- claude 2026-10-19: changed: the postgres job does not gate releases --> Local staging does not publish to GitHub Packages.

<!-- claude 2026-10-20: changed: links the toolchain, which now lives on the Releases page -->

Run the same core checks from a clean checkout with the [toolchain](../content/2.reference/2.releases.md#toolchain) of the Releases page:

<!-- claude 2026-10-20: changed: the fixtures sit in scala-modules/*/test-js (#23) -->

```sh
pnpm install --frozen-lockfile --ignore-scripts
node --test scripts/*.test.mjs packages/nuxt/test/*.test.mjs
for fixture in scala-modules/*/test-js; do npm ci --prefix "$fixture" --ignore-scripts; done
sbt 'h3Ext/test' 'kyoH3Ext/test' 'kyoNuxtHubExt/test' 'nativeConverterKyoSchemaExt/test' \
  'zioH3Ext/test' 'zioNuxtHubExt/test' 'nativeConverterZIOSchemaExt/test' \
  'nativeConverterZIOJsonExt/compile' 'zioMigrationsExt/compile' 'nuxtsvr/test'
pnpm run scala:full
sbt releaseCheck
node scripts/check-release.mjs --allow-snapshot
sbt releasePublishLocal
node scripts/check-consumers.mjs
```

The installation step skips Nuxt's postinstall preparation because these library checks do not start Nuxt. Use the ordinary installation and startup commands in the development guide when running the application. Each `test-js` directory pins the JavaScript runtime that a test suite loads: `h3@1.15.4` for the h3, Kyo, ZIO, and schema suites, and `unstorage` for the storage suites. sbt puts them on `NODE_PATH` for the tests.

Run sbt commands one at a time. `pnpm run scala:full` and `check-consumers.mjs` start their own sbt processes, so stop an sbt server or `sbt --client` session for this build first.

`releaseCheck` generates the intended library POMs. `check-release.mjs` reads them and refuses a release when:

- `build.sbt`'s `releaseModules` and the script's module list differ;
- a module does not build with Scala 3.9.0, or a POM has the wrong organization, artifact, or version;
- a published dependency leaks a local repository, a generated ScalablyTyped facade, or an internal artifact that is not released, or an internal dependency has another version;
- a Kyo module (`kyo-h3-ext`, `kyo-nuxthub-ext`, `native-converter-kyo-schema-ext`) depends on ZIO, or `h3-ext` depends on any effect runtime, or a ZIO converter module depends on Kyo;
- a module other than a schema bridge depends on a schema library, or a schema bridge depends on a JSON or YAML library;
- the version is a `-SNAPSHOT`. Pull request validation passes `--allow-snapshot` to accept one; a tag never accepts it, with or without the flag;
- on a tag, the tag is not exactly `v<version>`.

Test-scoped dependencies are ignored, because they do not reach consumers. `releasePublishLocal` publishes only the reusable modules to local Ivy for validation. `releasePublish` publishes that same module set to `https://maven.pkg.github.com/yuri4n/h34s`.

`check-consumers.mjs` redirects publication to a disposable local Maven repository. It accepts the snapshot version there. It creates four independent Scala 3.9.0 projects that depend only on release coordinates, with Maven Central and the staged repository as their resolvers. None references repository source projects or locally published Ivy artifacts:

- **zio**: `zio-h3-ext` and `zio-nuxthub-ext`. It may resolve Kyo, but no schema library.
- **kyo**: `kyo-h3-ext` and `kyo-nuxthub-ext`. It must not resolve any `dev.zio` artifact, `kyo-zio`, or a schema library.
- **zio-schema**: `zio-h3-ext` with the ZIO Schema and ZIO JSON converters. It must not resolve Kyo Schema. It also checks decimal JSON conversion without a Float precision loss.
- **kyo-schema**: `kyo-h3-ext` with `native-converter-kyo-schema-ext`. It must not resolve any `dev.zio` artifact.

Each project compiles, links, checks its resolved dependencies, and serves real HTTP requests through h3's preemptive router, which Nitro also uses. The checks cover the wire format: a value body is `{"data": ...}`, an internal failure is an opaque 500 `{"errors":[{"message":"Internal server error"}]}` without diagnostics, and the schema projects answer invalid input with 422 entries that carry the field `path`, a wrong shape with one generic entry, and invalid JSON with 400. The zio and kyo projects also store and read a value with their storage adapter through a fake h34s Nitro startup record with an in-memory mount; the adapter test suites cover the storage contract against the pinned unstorage fixture. The script removes its temporary projects and Maven repository afterward.

`pnpm run scala:full` clears previous linked output before linking both application modules for production, then checks that their output exists. The normal `pnpm build` command runs this step before Nuxt's production build, so a failed link cannot package stale development code.

Released tags, such as `v0.1.0-alpha.1` and `v0.2.0-alpha.1`, must not be moved. For each release:

1. Release the npm package with the same version first (see below), because the Scala libraries need its Nitro plugin.
2. Update the Maven version in `version.sbt`.
3. Write `.github/release-notes/<version>.md`. Start from `.github/release-notes/unreleased.md`, which collects notes while work lands: rename it with `git mv`, replace every `NEXT_VERSION`, delete its HTML comment and placeholder paragraph, and write the introduction and sections that earlier notes use (for example `0.2.0-alpha.1.md`). The file is published as written. After the release, create a new `unreleased.md` for the next one. The release job creates the GitHub prerelease from the file after publication; `scripts/check-release.mjs` refuses a tag without it, before anything is published. <!-- claude 2026-10-19: changed: start from the placeholder notes -->
4. Review the commit and checks, then create its matching `v<version>` tag. The tag workflow validates that exact revision, then publishes packages using `GITHUB_ACTOR` and the repository's `GITHUB_TOKEN`. It next runs `node scripts/check-consumers.mjs --published` against GitHub Packages to verify all four consumers through the authenticated registry, without local staging. Only the package publication job receives `packages: write`, which also permits this download check. A separate job receives `contents: write` to create the GitHub prerelease after publication and registry verification succeed. No additional release secret is required, and repository/package visibility remains private. For `0.2.0-alpha.1`, the [release workflow](https://github.com/yuri4n/h34s/actions/runs/39145732257) ran these jobs on the `v0.2.0-alpha.1` tag and created the [prerelease](https://github.com/yuri4n/h34s/releases/tag/v0.2.0-alpha.1).

Treat published release versions as immutable. If a release stops after some packages have uploaded, inspect the workflow and registry before retrying; do not overwrite artifacts with different contents under the same version. A corrected release should use a new version and matching tag.

## Release the npm build tooling separately

`packages/nuxt/package.json` owns the npm version. Its `npm-v<version>` tag workflow is separate from the Maven `v<version>` workflow and targets `https://npm.pkg.github.com`. Releasing or consuming the build tooling does not republish Scala artifacts. The initial npm version `0.1.0-alpha.1` is published under [its own immutable tag](https://github.com/yuri4n/h34s/releases/tag/npm-v0.1.0-alpha.1). Version `0.2.0-alpha.1` is published under [`npm-v0.2.0-alpha.1`](https://github.com/yuri4n/h34s/releases/tag/npm-v0.2.0-alpha.1). Its tag points at the same commit as the Maven `v0.2.0-alpha.1` tag, and its workflow finished before the Maven release started.

Validate package tests and a packed tarball before publishing. The tarball must contain the CLI, Nuxt module, runtime plugin, configuration helpers, and source-map/development loaders; a workspace import alone does not prove the published contents are complete. Validate an external Nuxt consumer as well as the repository example, then inspect the separate npm release checks before recommending registry installation. Keep package versions immutable and pin the application dependency.

The npm workflow gates publication on Node 22.18.0/24 package checks and Nuxt 4.3.1 development/production HTTP checks. It publishes using the repository's `GITHUB_TOKEN` with `packages: write`, then downloads the exact registry version into a fresh cache. The downloaded archive must match the tested bytes before an isolated offline installation verifies its CLI and three JavaScript entry points. The GitHub release includes that tarball and sanitized `registry.json` metadata with its registry location and integrity. For `0.2.0-alpha.1`, the [npm release workflow](https://github.com/yuri4n/h34s/actions/runs/39144809255) passed these gates, and the registry download matched the tested archive byte for byte. For `0.1.0-alpha.1`, Nuxt 4.4.2 also passed the same HTTP checks locally on Linux x86_64 with Node 24.19.0; it is an explicit additional profile, not evidence for all Nuxt 4.x releases or Nix/NixOS.
