# h34s

A toolbox for running Scala.js server code in your Nuxt/Nitro application.

The intended future name is **nuxt4s**. Current packages, commands, and links still use h34s; see the [project direction and rename plan](docs/project-direction.md).

Choose the native h3 bindings, ZIO or Kyo handler API, and optional adapters you need. Define your own models, services, and resource policy in your application. Use the npm tooling to link Scala.js and load it in Nuxt. Start with the [adoption guide](docs/adoption.md) to add these tools to your project.

The runnable demo is in [`examples/nuxt/`](examples/nuxt/README.md). The documentation site is a separate Nuxt app in [`website/`](website/README.md). The repository root holds the libraries and shared tooling; you do not need to copy the demo to use h34s.

**Status: [Maven alpha `0.1.0-alpha.1` released](https://github.com/yuri4n/h34s/releases/tag/v0.1.0-alpha.1).** Eleven independently consumable `dev.yuri4n` libraries are available through private GitHub Packages, including native h3 bindings, ZIO/Kyo APIs, and optional Schema and NuxtHub KV adapters. See the [installation and release guide](docs/releases.md) for authentication, coordinates, and validation.

Reusable build scripts and Nuxt development integration are published as **`@yuri4n/h34s-nuxt@0.1.0-alpha.1`** in the [separate npm release](https://github.com/yuri4n/h34s/releases/tag/npm-v0.1.0-alpha.1). The [npm tooling guide](docs/npm-tooling.md) covers installation and application configuration. Applications keep their sbt build and services; the package supplies the CLI, source-map support, development loading, and diagnostics plugin.

Both effect APIs use their own runtimes, native conversion, and application-owned services. The ZIO example also covers streaming and Skunk. Kyo streaming and Kyo Schema adapters, automatic route/type generation, and application packaging remain planned.

## Write application code in ZIO

Define your services, then write your own `AppHandler` to provide them before calling `ZHandler.define`. Follow the [handler guide](website/content/1.guide/3.handlers.md#compose-services-once-in-application-code) for the example composition. `AppHandler` and `Notes` below are application code you create; h34s does not export them. With that setup, write the endpoint as:

```scala
val listNotes = AppHandler.define:
    ZIO.serviceWithZIO[Notes](_.list)
```

Keep your models and connection policy in your application module. To try the optional Notes example, use its `Services.layer` to compose database configuration, a scoped Skunk connection, and `NotesLive`. The h34s boundary supplies request context, scopes execution, handles native conversion, and runs the effect.

Schema support is optional. The plain `ZHandler.readBody[A: NativeConverter]` API remains complete without schema dependencies. The Notes example opts into `ZHandler.readBodyWithSchema[CreateNote]`, keeping trimming and validation in the model's ZIO Schema. See the [optional schema guide](docs/schema.md) for the explicit native-value bridge and its supported forms.

To run the demo, follow the [development guide](docs/development.md). It covers the stream route that needs no database and the optional PostgreSQL insert/read example. Use the [adoption guide](docs/adoption.md) for your own Nuxt or Scala.js project.

Nuxt development mode exposes failure details and maps generated JavaScript back to Scala source locations. The [debugging guide](docs/debugging.md) explains diagnostics and Node debugging; production error responses stay opaque.

## Write application code in Kyo

Define a service such as `Greetings` in your application. Write your own `AppHandler` to resolve it with `Env.run` before calling `KHandler.define`, following the [Kyo guide](docs/kyo.md). Then request the service in the endpoint:

```scala
val greeting = AppHandler.define:
    Env.use[Greetings](_.greet("Kyo"))
```

`KHandler.define` supplies request context, runs Kyo, scopes cleanup, and converts the response. It does not depend on ZIO. See the [Kyo guide](docs/kyo.md) for models, body decoding, service composition, lifecycle limits, and a runnable Nitro example.

The `AppHandler` name is a convention used by the demos. Call `ZHandler.define` or `KHandler.define` directly when you do not need an application wrapper.

## Documentation website

The separate [Nuxt/Docus website](website/README.md) uses the shared
`yuri4n.dev` docs layer. From the repository root:

```sh
git submodule update --init --recursive
pnpm --dir website install --frozen-lockfile
pnpm docs:dev
```

The website runs on port 3001 without Scala linking or PostgreSQL. Its own
lockfile keeps documentation dependencies separate from the example app.

## Module direction

Keep one repository with separately consumable native h3 bindings, ZIO integration, and a first-class Kyo integration. ZIO is the main documented experience; each effect API uses its own runtime. See [architecture and lifecycle](docs/architecture.md) for the agreed boundaries and next slices.

## What is here

- `scala-modules/exts/`: native h3 bindings, ZIO handlers, conversion integrations, streaming tests, and recovered experimental migration helpers.
- `examples/nuxt/`: the optional Nuxt integration demo, its UI/content, route mounts, and application configuration.
- `examples/nuxt/scala/kyo/`: Kyo models, application services, `AppHandler`, and exported GET/POST handlers.
- `examples/nuxt/scala/zio/`: the Notes model, Skunk service, environment composition, handlers, and schema, alongside recovered playground services.
- `packages/nuxt/`: reusable npm CLI and Nuxt module, with a separate release lifecycle.
- `examples/nuxt/h34s.config.mjs`: demo-specific sbt project IDs, generated outputs, and expected route entry files. Its build wrapper lets root commands use the shared sbt build.
- `examples/nuxt/server/api/`: demo mounts for linked Scala.js handlers. Generated output is ignored.
- `website/`: the independently installed documentation site.
- `scripts/smoke.mjs`: direct HTTP streaming and database insert/read checks against a running development instance.

The [baseline report](docs/baseline.md) records the original import and its historical limitations. The current integration removes source rewriting and the missing `TaskStream` reference. `pnpm build` now links both example applications for production before building Nuxt, with a guard against stale linked output. Full application packaging, Nix support, and Scala-to-TypeScript generation remain separate work.

Use small feature branches, focused commits, and reviewable pull requests. [AGENTS.md](AGENTS.md) records the detailed project direction and delivery rules.
