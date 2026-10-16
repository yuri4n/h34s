# Adoption paths

Choose the h34s modules you need, write your models and services in your application, and mount your Scala.js exports in Nuxt route files. Choose either ZIO or Kyo for effect execution; each integration supplies its own request context and runtime boundary.

## New project

Create your Nuxt project with the Nuxt tooling, then follow the integration steps below to add an sbt/Scala.js module. Install the [published Maven modules](releases.md) for your chosen effect API and the [npm build tooling](npm-tooling.md), both at `0.1.0-alpha.1`. Define your application model and services yourself; h34s does not generate an application scaffold or an `AppHandler` object.

To explore a working integration first, run the optional `examples/nuxt/` demo using [development.md](development.md). Start with the route that needs no database; add the Notes/Skunk example only if you want to try PostgreSQL. The demo and shared repository build are development fixtures, not dependencies you need to copy into your project. There is no `create-h34s` command.

## Existing Nuxt application

Keep your TypeScript handlers. Add a Scala.js/sbt build alongside them and an application module whose `fastLinkJS` output points to `server/.scala`. The current configured ZIO profile uses Scala 3.5.2, Scala.js 1.22.0, h3 1.15.4, and the repository's resolved ZIO versions. The [release guide](releases.md#toolchain) lists the complete toolchain and the separate Kyo compiler requirement. The [schema compatibility table](schema.md#dependencies-and-compatibility) distinguishes current configuration from earlier local verification; other version/platform combinations need their own verification.

For ZIO, add `"dev.yuri4n" %%% "zio-h3-ext" % "0.1.0-alpha.1"` with the registry authentication in [releases.md](releases.md). For Kyo, choose `kyo-h3-ext` at the same version and follow the [Kyo compiler profile](kyo.md). Each dependency brings its native bindings transitively. Install npm `h3@1.15.4` in your Nuxt application as well. Source development can instead use explicit sbt project references from a checkout/submodule. Keep your application module independent of the demo's services and the experimental migration module.

Schema support is optional in every path. The NativeConverter-only reader is complete without schema dependencies. Applications that want model-defined validation can additionally depend on `zio-h3-schema-ext` at the same release version, or reference `nativeConverterZIOSchemaExt` and `zioH3SchemaExt` when integrating source projects. See [schema.md](schema.md) for the explicit API and supported forms.

Export a Scala handler with `@JSExportTopLevel("default", "notes-get")`. For the current integration, mount it once in `server/api/notes.get.ts`:

```ts
export { default } from '../.scala/notes-get.js'
```

Keep your existing Nuxt routes. Configure the [npm tooling](npm-tooling.md) with your sbt project, output directory, and route entry files. Its Nuxt module supplies development loading, source maps, and error-mode integration. Run `h34s-scala dev` before Nuxt, `h34s-scala watch` during edits, and `h34s-scala build` before production packaging. Write your own `AppHandler` wrapper if you want to share ZIO layer or Kyo environment composition across endpoints. Configure database resources and schema separately when your application needs them.

The Maven release verified independent ZIO, Kyo, and Schema consumers. The npm tooling also passed packed-package checks in minimal external Nuxt consumers; see its [verified profiles](npm-tooling.md). These checks do not establish every full application setup or deployment. Create route mounts yourself until automatic mounting is implemented, and install only the dependencies your application needs.

## Existing Scala application

For an existing Scala.js application, add a Nuxt frontend and a dedicated Scala.js server module with ES-module output. Add the h34s library dependency or source project references as above, keep your models/services in your own application modules, and mount exports in Nuxt. Compile shared models and service code for Scala.js; JVM-only dependencies cannot enter the Nitro bundle.

For a JVM-only application, share platform-compatible models through a cross-built module, or keep the JVM server and call it over HTTP. JDBC/Doobie does not compile to Scala.js. The selected h34s example uses Skunk directly in Nitro; adding Nuxt to a JVM application does not silently migrate its database runtime.

A clean consumer project for each adoption path, generated TypeScript wire types, and the installation helper are subsequent deliverables. They should be demonstrated before these integration recipes are presented as verified one-command setup flows.
