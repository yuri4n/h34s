# Adoption paths

The same separation applies in each path: Nuxt mounts handlers, Scala.js implements them, and application modules provide models and service layers. The ZIO library owns request context and effect execution.

## New project

Use the repository example and the commands in [development.md](development.md). The dependency-free stream route is the first check; add the Notes/Skunk example next. This path is exercised in the prepared cloud checkout. A fresh machine without the cloud helper and local Nuxt settings has not been validated. There is no `create-h34s` command. The `0.1.0-alpha.1` Maven libraries are published, as described in [releases.md](releases.md). The separate [npm build tooling](npm-tooling.md) is published as `@yuri4n/h34s-nuxt@0.1.0-alpha.1` in the private GitHub npm registry.

## Existing Nuxt application

Keep your TypeScript handlers. Add a Scala.js/sbt build alongside them and an application module whose `fastLinkJS` output points to `server/.scala`. The current configured ZIO profile uses Scala 3.5.2, Scala.js 1.22.0, h3 1.15.4, and the repository's resolved ZIO versions. The [release guide](releases.md#toolchain) lists the complete toolchain and the separate Kyo compiler requirement. The [schema compatibility table](schema.md#dependencies-and-compatibility) distinguishes current configuration from earlier local verification; other version/platform combinations need their own verification.

Use `"dev.yuri4n" %%% "zio-h3-ext" % "0.1.0-alpha.1"` with the registry authentication in [releases.md](releases.md). This dependency brings the native h3 bindings and ZIO conversion helpers transitively. Install npm `h3@1.15.4` in the Nuxt application as well. Source development can instead use explicit sbt project references from a checkout/submodule. Keep the application module independent of the recovered `nuxtsvr` example, its services, and the experimental migration module.

Schema support is optional in every path. The NativeConverter-only reader is complete without schema dependencies. Applications that want model-defined validation can additionally depend on `zio-h3-schema-ext` at the same release version, or reference `nativeConverterZIOSchemaExt` and `zioH3SchemaExt` when integrating source projects. See [schema.md](schema.md) for the explicit API and supported forms.

Export a Scala handler with `@JSExportTopLevel("default", "notes-get")`. For the current integration, mount it once in `server/api/notes.get.ts`:

```ts
export { default } from '../.scala/notes-get.js'
```

Nuxt's existing routes continue to work. Configure the [npm tooling](npm-tooling.md) with your sbt project, output directory, and route entry files. Its Nuxt module supplies development loading, source maps, and error-mode integration. Use `h34s-scala dev` before Nuxt, `h34s-scala watch` during edits, and `h34s-scala build` before production packaging. Application code owns `AppHandler.define`, `ZLayer` composition, and database setup. The library API does not require endpoints to construct a runtime or Promise.

This path describes the integration boundary; a fresh external Nuxt consumer and automated mounting have not been validated. The Maven release verified independent ZIO, Kyo, and Schema consumers, which is narrower than a full Nuxt setup. The npm tooling has a separate package and validation lifecycle. Use only the dependencies your application needs.

## Existing Scala application

For an existing Scala.js application, add a Nuxt frontend and a dedicated Scala.js server module with ES-module output. Add the h34s library dependency or source project references as above, keep your models/services in your own application modules, and mount exports in Nuxt. Compile shared models and service code for Scala.js; JVM-only dependencies cannot enter the Nitro bundle.

For a JVM-only application, share platform-compatible models through a cross-built module, or keep the JVM server and call it over HTTP. JDBC/Doobie does not compile to Scala.js. The selected h34s example uses Skunk directly in Nitro; adding Nuxt to a JVM application does not silently migrate its database runtime.

A clean consumer project for each adoption path, generated TypeScript wire types, and the installation helper are subsequent deliverables. They should be demonstrated before these integration recipes are presented as verified one-command setup flows.
