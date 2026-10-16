# Install and release h34s libraries

**`dev.yuri4n` version `0.1.0-alpha.1` is published** in the private GitHub Packages Maven registry for `yuri4n/h34s`. The [prerelease](https://github.com/yuri4n/h34s/releases/tag/v0.1.0-alpha.1) and [successful release workflow](https://github.com/yuri4n/h34s/actions/runs/38779659501) record publication of all eleven artifacts and authenticated ZIO/Kyo/Schema consumer checks.

The reusable JavaScript tooling is published separately as `@yuri4n/h34s-nuxt@0.1.0-alpha.1` in the [npm release](https://github.com/yuri4n/h34s/releases/tag/npm-v0.1.0-alpha.1). The Maven tag above remains unchanged. See [npm-tooling.md](npm-tooling.md) for package installation, shared configuration, and local tarball review.

## Toolchain

Use Node 24, pnpm 10.34.4, sbt 1.11.7, and Scala.js 1.22.0. The ZIO and native modules compile with Scala 3.5.2. Kyo modules use Scala 3.9.0 and Kyo 1.0.0-RC7; their macros require Java 25 at build time. Java 25 builds both APIs. Java and sbt are build tools; the application runs inside Nitro/Node.

Scala 3 dependency names have the `_sjs1_3` suffix. Use sbt's `%%%` syntax to select it. A ZIO-only application does not need Kyo, and a Kyo-only application does not need ZIO.

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
    scalaVersion := "3.5.2",
    libraryDependencies +=
      "dev.yuri4n" %%% "zio-h3-ext" % "0.1.0-alpha.1",
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
  )
```

For Kyo, use Scala `3.9.0` and replace `zio-h3-ext` with `kyo-h3-ext`. Keep Java 25 selected when compiling Kyo application code. These dependencies bring their respective runtime and the shared native bindings transitively; consumers do not run ScalablyTyped or copy generated facade artifacts from a developer's cache.

Code that used the earlier generated `typings.h3` or `typings.std` namespaces should use `dev.yuri4n.h34s.facades.h3` or `dev.yuri4n.h34s.facades.std`. The release packages these facades in `h3-ext` under its own namespace so they can coexist with other ScalablyTyped integrations.

Install h3 in the Nuxt application as a runtime dependency:

```sh
pnpm add h3@1.15.4
```

Maven dependencies supply Scala.js code, while pnpm supplies the JavaScript module imported by that code. See [adoption](adoption.md) for linking output, mounting exports alongside TypeScript routes, and composing application services outside handlers. See [development](development.md) for the complete repository example and [Kyo](kyo.md) for its independent application entry point.

## Independently consumable modules

Every row uses organization `dev.yuri4n`, version `0.1.0-alpha.1`, and Scala.js coordinates selected by `%%%`.

| Artifact | Purpose |
| --- | --- |
| `h3-ext` | Native h3 bindings and transport types, without an effect runtime |
| `zio-h3-ext` | ZIO handlers, request context, safe errors, and streaming |
| `kyo-h3-ext` | Kyo handlers on Kyo's own runtime |
| `zio-js-ext` | ZIO helpers for JavaScript interoperation |
| `native-converter-zio-ext` | NativeConverter support for ZIO data types |
| `native-converter-zio-json-ext` | NativeConverter support for ZIO JSON values |
| `native-converter-zio-schema-ext` | Optional native conversion from ZIO Schema |
| `zio-h3-schema-ext` | Optional schema-based request decoding for ZIO handlers |
| `nuxthub-ext` | Native NuxtHub 0.10.8 KV contract, supplied by the application |
| `zio-nuxthub-ext` | Optional ZIO KV adapter |
| `kyo-nuxthub-ext` | Optional Kyo KV adapter |

See [NuxtHub KV](nuxthub.md) for application-owned storage configuration and driver limitations.

Schema users can add `zio-h3-schema-ext` alongside `zio-h3-ext`; it includes the native schema converter. Schema derivation for application models can be added explicitly with `"dev.zio" %%% "zio-schema-derivation" % "1.7.5"`. Plain NativeConverter handlers do not require either schema integration. See [schema](schema.md) for supported forms and validation behavior.

The root project, aggregates, applications, examples, and experimental `zioMigrationsExt` are excluded from publication. Database services, configuration, models, and `AppHandler` composition stay in application modules. The reusable libraries do not require the example's Skunk database.

The alpha publication includes binary and source jars. Scaladoc jars are omitted because Scala 3.5's documentation generator fails while reading the generated inline h3 facades; a fix remains pending. The [documentation website](https://github.com/yuri4n/h34s/tree/v0.1.0-alpha.1/website/content) and source jars remain the API guides for this alpha.

## Validate and publish a release

The Scala workflow checks the development integration, ZIO/Kyo handlers, optional schema and NuxtHub KV adapters, application tests, production linking, and reusable-library POMs. It stages library packages locally and verifies independent Maven consumers before a release can proceed. Local staging does not publish to GitHub Packages.

Run the same core checks from a clean checkout with the toolchain above:

```sh
pnpm install --frozen-lockfile --ignore-scripts
node --test scripts/*.test.mjs packages/nuxt/test/*.test.mjs
npm ci --prefix scala-modules/exts/nuxthub/test-js --ignore-scripts
sbt 'zioH3Ext/test' 'kyoH3Ext/test' \
  'nativeConverterZIOSchemaExt/test' 'zioH3SchemaExt/test' 'nuxtsvr/test' \
  'zioNuxtHubExt/test' 'kyoNuxtHubExt/test'
pnpm run scala:full
sbt releaseCheck
node scripts/check-release.mjs
sbt releasePublishLocal
node scripts/check-consumers.mjs
```

The installation step skips Nuxt's postinstall preparation because these library checks do not start Nuxt. Use the ordinary installation and startup commands in the development guide when running the application.

`releaseCheck` generates the intended library POMs. The metadata check rejects inconsistent release versions, unexpected internal dependencies, local generated-facade dependencies, and a tag that does not exactly match the configured version. `releasePublishLocal` publishes only the reusable modules to local Ivy for validation. `releasePublish` publishes that same module set to `https://maven.pkg.github.com/yuri4n/h34s`.

`check-consumers.mjs` redirects publication to a disposable local Maven repository. It creates three independent projects: Scala 3.5.2/ZIO, Scala 3.9.0/Kyo, and ZIO with the optional Schema and JSON adapters. They depend only on release artifact coordinates, with Maven Central and the staged repository as their resolvers. None references repository source projects or locally published Ivy artifacts. Each compiles, links, checks that the other effect runtime is absent, and serves a real HTTP request through h3. The base ZIO consumer also checks that it has no optional Schema dependency. The Schema consumer decodes a request with `readBodyWithSchema` and checks decimal JSON conversion without a Float precision loss.

The consumers also compile their respective NuxtHub KV adapter constructors. KV operations are exercised separately by the adapter test suites against the pinned JavaScript fixture; the consumer HTTP checks do not start NuxtHub or a storage service. The script removes its temporary projects and Maven repository afterward.

`pnpm run scala:full` clears previous linked output before linking both application modules for production, then checks that their output exists. The normal `pnpm build` command runs this step before Nuxt's production build, so a failed link cannot package stale development code.

The `v0.1.0-alpha.1` tag is already released and must not be moved. For a future release, update the Maven version, review the commit and checks, then create its matching `v<version>` tag. The tag workflow validates that exact revision, then publishes packages using `GITHUB_ACTOR` and the repository's `GITHUB_TOKEN`. It next runs `node scripts/check-consumers.mjs --published` against GitHub Packages to verify all three consumers through the authenticated registry, without local staging. Only the package publication job receives `packages: write`, which also permits this download check. A separate job receives `contents: write` to create the GitHub prerelease after publication and registry verification succeed. No additional release secret is required, and repository/package visibility remains private.

Treat published release versions as immutable. If a release stops after some packages have uploaded, inspect the workflow and registry before retrying; do not overwrite artifacts with different contents under the same version. A corrected release should use a new version and matching tag.

## Release the npm build tooling separately

`packages/nuxt/package.json` owns the npm version. Its `npm-v<version>` tag workflow is separate from the Maven `v<version>` workflow and targets `https://npm.pkg.github.com`. Releasing or consuming the build tooling does not republish Scala artifacts. The initial npm version `0.1.0-alpha.1` is published under [its own immutable tag](https://github.com/yuri4n/h34s/releases/tag/npm-v0.1.0-alpha.1).

Validate package tests and a packed tarball before publishing. The tarball must contain the CLI, Nuxt module, runtime plugin, configuration helpers, and source-map/development loaders; a workspace import alone does not prove the published contents are complete. Validate an external Nuxt consumer as well as the repository example, then inspect the separate npm release checks before recommending registry installation. Keep package versions immutable and pin the application dependency.

The npm workflow gates publication on Node 22.18.0/24 package checks and Nuxt 4.3.1 development/production HTTP checks. It publishes using the repository's `GITHUB_TOKEN` with `packages: write`, then downloads the exact registry version into a fresh cache. The downloaded archive must match the tested bytes before an isolated offline installation verifies its CLI and three JavaScript entry points. The GitHub release includes that tarball and sanitized `registry.json` metadata with its registry location and integrity. Nuxt 4.4.2 also passed the same HTTP checks locally on Linux x86_64 with Node 24.19.0; it is an explicit additional profile, not evidence for all Nuxt 4.x releases or Nix/NixOS.
