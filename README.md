# h34s

A Scala.js toolbox for Nuxt/Nitro server handlers, with developer experience as a first-class requirement.

**Status: unpublished ZIO and Kyo development integrations.** Both APIs use their own runtimes, native conversion, and application-owned services. The ZIO example also covers streaming and Skunk. Kyo streaming and Schema adapters, published artifacts, automatic route/type generation, and production packaging remain planned.

## Write application code in ZIO

The application supplies its services in `AppHandler`; endpoint code stays small:

```scala
val listNotes = AppHandler.define:
    ZIO.serviceWithZIO[Notes](_.list)
```

The example's `Services.layer` composes database configuration, a scoped Skunk connection, and `NotesLive`. Models and connection policy live in the application module. The library supplies request context, scopes execution, handles native conversion, and hides runtime/Promise plumbing.

Start with the [development guide](docs/development.md). It covers the dependency-free stream route and the PostgreSQL insert/read example. The [adoption guide](docs/adoption.md) describes new projects, existing Nuxt applications, and existing Scala projects, including current validation limits.

Nuxt development mode exposes failure details and maps generated JavaScript back to Scala source locations. The [debugging guide](docs/debugging.md) explains diagnostics and Node debugging; production error responses stay opaque.

## Write application code in Kyo

The separate Kyo application supplies `Greetings` with ordinary `Env.run` in its own `AppHandler`:

```scala
val greeting = AppHandler.define:
    Env.use[Greetings](_.greet("Kyo"))
```

`KHandler.define` supplies request context, runs Kyo, scopes cleanup, and converts the response. It does not depend on ZIO. See the [Kyo guide](docs/kyo.md) for models, body decoding, service composition, lifecycle limits, and a runnable Nitro example.

## Module direction

Keep one repository with separately consumable native h3 bindings, ZIO integration, and a first-class Kyo integration. ZIO is the main documented experience; each effect API uses its own runtime. See [architecture and lifecycle](docs/architecture.md) for the agreed boundaries and next slices.

## What is here

- `scala-modules/exts/`: native h3 bindings, ZIO handlers, conversion integrations, streaming tests, and recovered experimental migration helpers.
- `scala-modules/kyo-example/`: Kyo models, application services, `AppHandler`, and exported GET/POST handlers.
- `scala-modules/svr/`: the application-owned Notes model, Skunk service, environment composition, handlers, and schema, alongside recovered playground services.
- `server/api/`: mounts for linked Scala.js handlers. Generated output is ignored.
- `scripts/smoke.mjs`: direct HTTP streaming and database insert/read checks against a running development instance.
- `app/` and `content/`: retained playground UI/content; this slice does not redesign the frontend.

The [baseline report](docs/baseline.md) records the original import and its historical limitations. This slice removes source rewriting and the missing `TaskStream` reference, but it does not establish the production linking/packaging path or Scala-to-TypeScript generation.

Use small feature branches, focused commits, and reviewable pull requests. [AGENTS.md](AGENTS.md) records the detailed project direction and delivery rules.
