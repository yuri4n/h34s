# h34s

A Scala.js toolbox for Nuxt/Nitro server handlers, with developer experience as a first-class requirement.

**Status: [Maven alpha `0.1.0-alpha.1` released](https://github.com/yuri4n/h34s/releases/tag/v0.1.0-alpha.1).** Eleven independently consumable `dev.yuri4n` libraries are available through private GitHub Packages, including native h3 bindings, ZIO/Kyo APIs, and optional Schema and NuxtHub KV adapters. See the [installation and release guide](docs/releases.md) for authentication, coordinates, and validation.

Reusable build scripts and Nuxt development integration live in **`@yuri4n/h34s-nuxt`**. Its separate npm release is pending; the [npm tooling guide](docs/npm-tooling.md) covers source-tarball review and the installation/configuration contract. Applications keep their sbt build and services; the package supplies the CLI, source-map support, development loading, and diagnostics plugin.

Both effect APIs use their own runtimes, native conversion, and application-owned services. The ZIO example also covers streaming and Skunk. Kyo streaming and Kyo Schema adapters, automatic route/type generation, and application packaging remain planned.

## Write application code in ZIO

The application supplies its services in `AppHandler`; endpoint code stays small:

```scala
val listNotes = AppHandler.define:
    ZIO.serviceWithZIO[Notes](_.list)
```

The example's `Services.layer` composes database configuration, a scoped Skunk connection, and `NotesLive`. Models and connection policy live in the application module. The library supplies request context, scopes execution, handles native conversion, and hides runtime/Promise plumbing.

Schema support is optional. The plain `ZHandler.readBody[A: NativeConverter]` API remains complete without schema dependencies. The Notes example opts into `ZHandler.readBodyWithSchema[CreateNote]`, keeping trimming and validation in the model's ZIO Schema. See the [optional schema guide](docs/schema.md) for the explicit native-value bridge and its supported forms.

Start with the [development guide](docs/development.md). It covers the dependency-free stream route and the PostgreSQL insert/read example. The [adoption guide](docs/adoption.md) describes new projects, existing Nuxt applications, and existing Scala projects, including current validation limits.

Nuxt development mode exposes failure details and maps generated JavaScript back to Scala source locations. The [debugging guide](docs/debugging.md) explains diagnostics and Node debugging; production error responses stay opaque.

## Write application code in Kyo

The separate Kyo application supplies `Greetings` with ordinary `Env.run` in its own `AppHandler`:

```scala
val greeting = AppHandler.define:
    Env.use[Greetings](_.greet("Kyo"))
```

`KHandler.define` supplies request context, runs Kyo, scopes cleanup, and converts the response. It does not depend on ZIO. See the [Kyo guide](docs/kyo.md) for models, body decoding, service composition, lifecycle limits, and a runnable Nitro example.

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
- `scala-modules/kyo-example/`: Kyo models, application services, `AppHandler`, and exported GET/POST handlers.
- `scala-modules/svr/`: the application-owned Notes model, Skunk service, environment composition, handlers, and schema, alongside recovered playground services.
- `packages/nuxt/`: reusable npm CLI and Nuxt module, with a separate release lifecycle.
- `h34s.config.mjs`: application-specific sbt project IDs, generated outputs, and expected route entry files.
- `server/api/`: mounts for linked Scala.js handlers. Generated output is ignored.
- `scripts/smoke.mjs`: direct HTTP streaming and database insert/read checks against a running development instance.
- `app/` and `content/`: retained playground UI/content; this slice does not redesign the frontend.

The [baseline report](docs/baseline.md) records the original import and its historical limitations. The current integration removes source rewriting and the missing `TaskStream` reference. `pnpm build` now links both example applications for production before building Nuxt, with a guard against stale linked output. Full application packaging, Nix support, and Scala-to-TypeScript generation remain separate work.

Use small feature branches, focused commits, and reviewable pull requests. [AGENTS.md](AGENTS.md) records the detailed project direction and delivery rules.
