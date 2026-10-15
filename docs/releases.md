# Install and release h34s libraries

The first planned release is **`dev.yuri4n` version `0.1.0-alpha.1`**, hosted in the private GitHub Packages Maven registry for `yuri4n/h34s`. This document describes the release configuration and installation recipe; it does not mean that the artifacts have been published. Check the repository's [releases](https://github.com/yuri4n/h34s/releases) and successful release workflow before using these coordinates.

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

A consumer GitHub Actions workflow can use its repository's `GITHUB_TOKEN` when that repository has access to these packages. Set `permissions: { contents: read, packages: read }`, provide `GITHUB_PACKAGES_USER: ${{ github.actor }}` and `GITHUB_PACKAGES_TOKEN: ${{ secrets.GITHUB_TOKEN }}` to the sbt step, and grant the consumer repository access in the package's Actions access settings if it does not inherit access. No developer PAT is needed for that workflow.

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

Schema users can add `zio-h3-schema-ext` alongside `zio-h3-ext`; it includes the native schema converter. Schema derivation for application models can be added explicitly with `"dev.zio" %%% "zio-schema-derivation" % "1.7.5"`. Plain NativeConverter handlers do not require either schema integration. See [schema](schema.md) for supported forms and validation behavior.

The root project, aggregates, applications, examples, and experimental `zioMigrationsExt` are excluded from publication. Database services, configuration, models, and `AppHandler` composition stay in application modules. The reusable libraries do not require the example's Skunk database.

The alpha publication includes binary and source jars. Scaladoc jars are omitted because Scala 3.5's documentation generator fails while reading the generated inline h3 facades; a fix remains pending. The [documentation website](https://github.com/yuri4n/h34s/tree/main/website/content) and source jars remain the API guides for this alpha.

## Validate and publish a release

The Scala workflow checks the development integration, ZIO/Kyo handlers, optional schema adapters, application tests, production linking, and reusable-library POMs. It stages library packages locally and verifies independent Maven consumers before a release can proceed. Local staging does not publish to GitHub Packages.

Run the same core checks from a clean checkout with the toolchain above:

```sh
pnpm install --frozen-lockfile --ignore-scripts
node --test scripts/*.test.mjs
sbt 'zioH3Ext/test' 'kyoH3Ext/test' \
  'nativeConverterZIOSchemaExt/test' 'zioH3SchemaExt/test' 'nuxtsvr/test'
pnpm run scala:full
sbt releaseCheck
node scripts/check-release.mjs
sbt releasePublishLocal
node scripts/check-consumers.mjs
```

The installation step skips Nuxt's postinstall preparation because these library checks do not start Nuxt. Use the ordinary installation and startup commands in the development guide when running the application.

`releaseCheck` generates the intended library POMs. The metadata check rejects inconsistent release versions, unexpected internal dependencies, local generated-facade dependencies, and a tag that does not exactly match the configured version. `releasePublishLocal` publishes only the reusable modules to local Ivy for validation. `releasePublish` publishes that same module set to `https://maven.pkg.github.com/yuri4n/h34s`.

`check-consumers.mjs` redirects publication to a disposable local Maven repository. It creates separate Scala 3.5.2/ZIO and Scala 3.9.0/Kyo projects that depend only on released artifact coordinates, with Maven Central and the staged repository as their resolvers. Neither consumer references repository source projects or locally published Ivy artifacts. Each compiles, links, checks that the other effect runtime and unwanted schema dependencies are absent, and serves a real HTTP request through h3. The script removes its temporary projects and Maven repository afterward.

`pnpm run scala:full` clears previous linked output before linking both application modules for production, then checks that their output exists. The normal `pnpm build` command runs this step before Nuxt's production build, so a failed link cannot package stale development code.

After the release commit is reviewed and its checks pass, a maintainer can create and push the matching `v0.1.0-alpha.1` tag. The tag workflow validates that exact revision, then publishes packages using `GITHUB_ACTOR` and the repository's `GITHUB_TOKEN`. It next runs `node scripts/check-consumers.mjs --published` against GitHub Packages to verify both consumers through the authenticated registry, without local staging. Only the package publication job receives `packages: write`, which also permits this download check. A separate job receives `contents: write` to create the GitHub prerelease after publication and registry verification succeed. No additional release secret is required, and repository/package visibility remains private.

Treat published release versions as immutable. If a release stops after some packages have uploaded, inspect the workflow and registry before retrying; do not overwrite artifacts with different contents under the same version. A corrected release should use a new version and matching tag.
