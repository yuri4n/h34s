# Nuxt integration demo

Run this optional application to try h34s handlers inside Nuxt/Nitro. It combines two independent Scala.js examples so you can compare their APIs:

- `scala/kyo/`: Kyo greeting handlers, models, and application service composition. No database is required.
- `scala/zio/`: ZIO handlers, streaming, and an optional PostgreSQL/Skunk Notes example.
- `server/api/`: TypeScript route files that mount the exported Scala handlers.
- `app/`, `content/`, and `public/`: the demo frontend and its content.

The documentation site lives in [`../../website/`](../../website/README.md). The reusable toolbox lives in `../../scala-modules/` and `../../packages/`. This demo is excluded from library publication.

## Run the demo

Use Node 24, pnpm 10.34.4, Java 25, and sbt 1.11.7. From the repository root, run:

```sh
pnpm install --frozen-lockfile
pnpm run scala:fast
pnpm dev
```

Request `http://localhost:3000/api/kyo/greeting` for a Kyo greeting. The response is `{"data":{"message":"Hello, Kyo!"}}`: with the default handler options, h34s wraps a value body in `data`. Request `/api/test` for the ZIO stream; it returns `abc`, because streamed bodies are sent as they are. Errors use one shape for both runtimes: `{"errors":[{"message":"...","path":"..."}]}`, where `path` names the invalid field when it is known.

The ZIO Notes routes (`/api/notes`) need PostgreSQL. Copy `.env.example` to `.env` in this directory and set your own `NUXT_DATABASE_PASSWORD`. Nitro maps the `NUXT_DATABASE_*` variables to `runtimeConfig.database` in `nuxt.config.ts`; Docker Compose reads the same names. For PostgreSQL setup, development linking, and production builds, follow the [development guide](../../docs/development.md).

Configure the example in `h34s.config.mjs`. The Nuxt module resolves paths relative to this application. `h34s.build.config.mjs` adapts those paths for the shared sbt build at the repository root. Root `pnpm dev`, `build`, `generate`, and `preview` commands delegate to this application; the production output is `.output/` here.

## Use h34s in your application

Follow the [adoption guide](../../docs/adoption.md) to install the published libraries and npm tooling in your own Nuxt project. Choose Kyo or ZIO, define your models and services, and create route files for your exported handlers. Add Schema or database dependencies only when you need them.

If several handlers share environment setup, write your own `AppHandler` wrapper. The wrappers in `scala/kyo/` and `scala/zio/` show how to provide services before calling `KHandler.define` or `ZHandler.define`. Adapt that composition to your application; `AppHandler` is an example-owned convention.
