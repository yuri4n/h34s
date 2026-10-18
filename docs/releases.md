# Install and release h34s libraries

**`dev.yuri4n` version `0.1.0-alpha.1` is published** in the private GitHub Packages Maven registry for `yuri4n/h34s`. The [prerelease](https://github.com/yuri4n/h34s/releases/tag/v0.1.0-alpha.1) and [successful release workflow](https://github.com/yuri4n/h34s/actions/runs/38779659501) record publication of all eleven artifacts and authenticated ZIO/Kyo/Schema consumer checks.

The current source releases **`0.2.0-alpha.1`**. This guide describes its installation and release configuration; it does not mean that the artifacts have been published. Check the repository's [releases](https://github.com/yuri4n/h34s/releases) and successful release workflows before using these coordinates. `0.2.0-alpha.1` changes the module set and the handler API: Kyo is the core runtime, the ZIO modules adapt it, and every module builds with Scala 3.9.0. Its [release notes](../.github/release-notes/0.2.0-alpha.1.md) summarize the changes. The tables and commands below describe `0.2.0-alpha.1`. Where the published `0.1.0-alpha.1` differs, this guide says so; its [release notes](../.github/release-notes/0.1.0-alpha.1.md) list what that version contains.

The reusable JavaScript tooling is published separately as `@yuri4n/h34s-nuxt@0.1.0-alpha.1` in the [npm release](https://github.com/yuri4n/h34s/releases/tag/npm-v0.1.0-alpha.1). The Maven tag above remains unchanged. See [npm-tooling.md](npm-tooling.md) for package installation, shared configuration, and local tarball review. The current source of that package is `0.2.0-alpha.1`, the same version as `version.sbt`, and its release tag is `npm-v0.2.0-alpha.1`. It registers a new Nitro plugin that the `0.2.0-alpha.1` Scala libraries need for runtime config, storage mounts, and application-scoped resources. The published `0.1.0-alpha.1` package does not have it. Use `@yuri4n/h34s-nuxt` at the Scala libraries' version or later, and release the npm package first.

## Toolchain

Use Node 24, pnpm 10.34.4, sbt 1.11.7, Scala.js 1.22.0, and Java 25. Every module compiles with Scala 3.9.0. The Kyo modules use Kyo 1.0.0-RC7; the ZIO modules use ZIO 2.1.26, the version that kyo-zio pins. Kyo's macros require Java 25 at build time, so build your application with Java 25 too. Java and sbt are build tools; the application runs inside Nitro/Node.

In `0.1.0-alpha.1`, the native and ZIO modules used Scala 3.5.2 and only the Kyo modules used Scala 3.9.0.

Scala 3 dependency names have the `_sjs1_3` suffix. Use sbt's `%%%` syntax to select it. A Kyo-only application never resolves ZIO. A ZIO application resolves Kyo too, because the ZIO adapter runs on the Kyo core.

## Authenticate package reads

Private package downloads require both access to `yuri4n/h34s` and package-read permission. On a developer machine, use a GitHub personal access token (classic) with `read:packages` from an account that can access the repository. If organization SSO applies, authorize the token for that organization.

Provide `GITHUB_PACKAGES_USER` and `GITHUB_PACKAGES_TOKEN` through your shell or secret manager. Keep the token out of committed sbt files, `.env` files included in builds, and command output. In a consumer's `build.sbt`, add:

```scala
ThisBuild / resolvers += "h34s GitHub Packages" at
  "https://maven.pkg.github.com/yuri4n/h34s"

ThisBuild / credentials ++= (for {
  user <- sys.env.get("GITHUB_PACKAGES_USER")
  token <- sys.env.get("GITHUB_PACKAGES_TOKEN")
} yield Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  user,
  token,
)).toSeq
```

GitHub's Maven registry uses [repository-scoped permissions](https://docs.github.com/en/packages/learn-github-packages/about-permissions-for-github-packages#permissions-for-repository-scoped-packages). The h34s repository's own workflows can publish and verify these packages with their `GITHUB_TOKEN`.

A different repository, including `yurian.land`, needs a classic PAT with `read:packages` from an account that can read private `yuri4n/h34s`. Store it as that consumer repository's `PACKAGES_READ_TOKEN` Actions secret and provide the token owner's username as the `PACKAGES_USER` Actions variable. GitHub reserves the `GITHUB_` prefix for saved secret and variable names. Map the saved values into the environment names read by sbt:

```yaml
env:
  GITHUB_PACKAGES_TOKEN: ${{ secrets.PACKAGES_READ_TOKEN }}
  GITHUB_PACKAGES_USER: ${{ vars.PACKAGES_USER }}
```

The consumer's own `GITHUB_TOKEN` and the granular package “Manage Actions access” settings do not establish cross-repository Maven access.

HTTP 401 usually means missing or invalid credentials. HTTP 403, or a package appearing absent despite a successful release, can mean that the account or workflow repository lacks access. Check the registry URL, package version, token scope, and package access before changing dependency versions.

## Add the API to a Scala.js application

Pin the build tools:

```properties
# project/build.properties
sbt.version=1.11.7
```

```scala
// project/plugins.sbt
addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")
```

For ZIO, add the registry and credentials above, then configure your application module:

```scala
import org.scalajs.linker.interface.ModuleKind

lazy val server = project.in(file("scala-server"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    scalaVersion := "3.9.0",
    libraryDependencies +=
      "dev.yuri4n" %%% "zio-h3-ext" % "0.2.0-alpha.1",
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
  )
```

For Kyo, replace `zio-h3-ext` with `kyo-h3-ext`. Both APIs use Scala `3.9.0`; in `0.1.0-alpha.1` the ZIO API used Scala `3.5.2`. Keep Java 25 selected when compiling. These dependencies bring their runtime and the shared native layer transitively.

The current `h3-ext` has hand-written, internal h3 and Nitro facades. Your handlers receive a `Request` value and return a body; they do not call facades. `0.1.0-alpha.1` instead packaged ScalablyTyped-generated facades under `dev.yuri4n.h34s.facades.h3` and `dev.yuri4n.h34s.facades.std`. `0.2.0-alpha.1` removes those packages.

Install h3 in the Nuxt application as a runtime dependency:

```sh
pnpm add h3@1.15.4
```

Maven dependencies supply Scala.js code, while pnpm supplies the JavaScript module imported by that code. See [adoption](adoption.md) for linking output, mounting exports alongside TypeScript routes, and composing application services outside handlers. See [development](development.md) for the complete repository example and [Kyo](kyo.md) for its independent application entry point.

## Independently consumable modules

Every row uses organization `dev.yuri4n` and Scala.js coordinates selected by `%%%`. The current source releases these nine library modules as `0.2.0-alpha.1`:

| Artifact | Purpose | Brings |
| --- | --- | --- |
| `h3-ext` | Native layer: request snapshot, response head, safe errors, `Secret`, internal h3/Nitro facades | No effect runtime |
| `kyo-h3-ext` | The core: `KHandler`, body reading, `RuntimeConfig`, `Shared`, streaming | Kyo |
| `kyo-nuxthub-ext` | Optional Kyo storage over Nitro mounts (`KHubKv.mount`) | Kyo |
| `native-converter-kyo-schema-ext` | Optional `derives NativeSchema` from a Kyo `Schema` | Kyo Schema |
| `zio-h3-ext` | The ZIO adapter: `ZHandler`, `ZRuntimeConfig`, `ZShared`, `ZStream` bodies | ZIO and Kyo |
| `zio-nuxthub-ext` | Optional ZIO storage over Nitro mounts (`ZHubKv.mount`) | ZIO and Kyo |
| `native-converter-zio-ext` | NativeConverter support for ZIO data types | ZIO, no Kyo |
| `native-converter-zio-json-ext` | NativeConverter support for ZIO JSON values | ZIO JSON, no Kyo |
| `native-converter-zio-schema-ext` | Optional `derives NativeSchema` from a ZIO `Schema` | ZIO Schema, no Kyo |

`0.1.0-alpha.1` published eleven artifacts. `0.2.0-alpha.1` removes three of them and adds one:

- `zio-js-ext` is removed. It no longer contained code.
- `nuxthub-ext` is removed. The storage adapters open a Nitro mount by name (`KHubKv.mount("kv")`) through the h34s Nitro plugin, so your application does not supply a native KV object.
- `zio-h3-schema-ext` is removed. Use `native-converter-zio-schema-ext` with `derives Schema, NativeSchema` and read the body with `request.body.json[A]`.
- `native-converter-kyo-schema-ext` is new.

See [NuxtHub KV](nuxthub.md) for storage configuration and driver limitations.

Schema support stays optional. Add `native-converter-zio-schema-ext` (with `"dev.zio" %%% "zio-schema-derivation" % "1.7.5"`) or `native-converter-kyo-schema-ext` next to your handler module when your models need one schema for validation and conversion. Plain `derives NativeConverter` models need neither. The schema bridges do not choose a JSON or YAML library for you: add `kyo-schema-json`, `kyo-schema-yaml`, or `zio-schema-json` yourself when you need those formats. See [schema](schema.md) for supported forms and validation behavior.

The root project, aggregates, applications, examples, and the experimental `zioMigrationsExt` are excluded from publication. Database services, configuration, models, and `AppHandler` composition stay in application modules. The reusable libraries do not require the example's Skunk database.

Releases include binary and source jars. `build.sbt` still omits Scaladoc jars. In `0.1.0-alpha.1` the reason was that Scala 3.5's documentation generator failed on the generated inline h3 facades. Those facades are gone, but publishing Scaladoc again has not been tested. The [documentation website](https://github.com/yuri4n/h34s/tree/v0.2.0-alpha.1/website/content) and source jars remain the API guides.

## Validate and publish a release

The Scala workflow checks the development integration, ZIO/Kyo handlers, optional schema and NuxtHub KV adapters, application tests, production linking, and reusable-library POMs. It stages library packages locally and verifies independent Maven consumers before a release can proceed. Local staging does not publish to GitHub Packages.

Run the same core checks from a clean checkout with the toolchain above:

```sh
pnpm install --frozen-lockfile --ignore-scripts
node --test scripts/*.test.mjs packages/nuxt/test/*.test.mjs
for fixture in scala-modules/exts/*/test-js; do npm ci --prefix "$fixture" --ignore-scripts; done
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

Released tags, such as `v0.1.0-alpha.1`, must not be moved. For each release:

1. Release the npm package with the same version first (see below), because the Scala libraries need its Nitro plugin.
2. Update the Maven version in `version.sbt`.
3. Write `.github/release-notes/<version>.md`. The release job creates the GitHub prerelease from it after publication; `scripts/check-release.mjs` refuses a tag without it, before anything is published.
4. Review the commit and checks, then create its matching `v<version>` tag. The tag workflow validates that exact revision, then publishes packages using `GITHUB_ACTOR` and the repository's `GITHUB_TOKEN`. It next runs `node scripts/check-consumers.mjs --published` against GitHub Packages to verify all four consumers through the authenticated registry, without local staging. Only the package publication job receives `packages: write`, which also permits this download check. A separate job receives `contents: write` to create the GitHub prerelease after publication and registry verification succeed. No additional release secret is required, and repository/package visibility remains private.

Treat published release versions as immutable. If a release stops after some packages have uploaded, inspect the workflow and registry before retrying; do not overwrite artifacts with different contents under the same version. A corrected release should use a new version and matching tag.

## Release the npm build tooling separately

`packages/nuxt/package.json` owns the npm version. Its `npm-v<version>` tag workflow is separate from the Maven `v<version>` workflow and targets `https://npm.pkg.github.com`. Releasing or consuming the build tooling does not republish Scala artifacts. The initial npm version `0.1.0-alpha.1` is published under [its own immutable tag](https://github.com/yuri4n/h34s/releases/tag/npm-v0.1.0-alpha.1).

Validate package tests and a packed tarball before publishing. The tarball must contain the CLI, Nuxt module, runtime plugin, configuration helpers, and source-map/development loaders; a workspace import alone does not prove the published contents are complete. Validate an external Nuxt consumer as well as the repository example, then inspect the separate npm release checks before recommending registry installation. Keep package versions immutable and pin the application dependency.

The npm workflow gates publication on Node 22.18.0/24 package checks and Nuxt 4.3.1 development/production HTTP checks. It publishes using the repository's `GITHUB_TOKEN` with `packages: write`, then downloads the exact registry version into a fresh cache. The downloaded archive must match the tested bytes before an isolated offline installation verifies its CLI and three JavaScript entry points. The GitHub release includes that tarball and sanitized `registry.json` metadata with its registry location and integrity. Nuxt 4.4.2 also passed the same HTTP checks locally on Linux x86_64 with Node 24.19.0; it is an explicit additional profile, not evidence for all Nuxt 4.x releases or Nix/NixOS.
