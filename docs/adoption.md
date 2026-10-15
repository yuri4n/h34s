# Adoption paths

The same separation applies in each path: Nuxt mounts handlers, Scala.js implements them, and application modules provide models and service layers. The ZIO library owns request context and effect execution.

## New project

Use the repository example and the commands in [development.md](development.md). The dependency-free stream route is the first check; add the Notes/Skunk example next. This path is exercised in the prepared cloud checkout. A fresh machine without the cloud helper and local Nuxt settings has not been validated. There is no published `create-h34s` command or release artifact yet.

## Existing Nuxt application

Keep your TypeScript handlers. Add a Scala.js/sbt build alongside them and an application module whose `fastLinkJS` output points to `server/.scala`. Use the Scala 3.5.2, Scala.js 1.20.1, h3 1.15.4, and ZIO versions from this repository until a compatibility matrix exists.

The current source integration needs the native h3, ZIO handler, ZIO JavaScript helper, and native-converter modules from this repository. They are not published dependencies yet; a source checkout/submodule and explicit sbt project references are the interim option. Keep the application module independent of the recovered `nuxtsvr` example, its services, and the experimental migration module.

Export a Scala handler with `@JSExportTopLevel("default", "notes-get")`. For the current integration, mount it once in `server/api/notes.get.ts`:

```ts
export { default } from '../.scala/notes-get.js'
```

Nuxt's existing routes continue to work. Run Scala linking before Nuxt; add a separate linking watch process during development. Application code owns `AppHandler.define`, `ZLayer` composition, and database setup. The library API does not require endpoints to construct a runtime or Promise.

This path describes the integration boundary; a fresh external Nuxt consumer and automated mounting have not been validated. Do not copy the playground's entire dependency set into an existing application or assume unpublished artifact coordinates.

## Existing Scala application

For an existing Scala.js application, add a Nuxt frontend and a dedicated Scala.js server module with ES-module output. Reference the h34s source modules as above, keep your models/services in your own application modules, and mount exports in Nuxt. Compile shared models and service code for Scala.js; JVM-only dependencies cannot enter the Nitro bundle.

For a JVM-only application, share platform-compatible models through a cross-built module, or keep the JVM server and call it over HTTP. JDBC/Doobie does not compile to Scala.js. The selected h34s example uses Skunk directly in Nitro; adding Nuxt to a JVM application does not silently migrate its database runtime.

A clean consumer project for each adoption path, generated TypeScript wire types, and the installation helper are subsequent deliverables. They should be demonstrated before these integration recipes are presented as verified one-command setup flows.
