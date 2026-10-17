# h34s

Write Nuxt server routes in Scala.js.

h34s is a toolbox for your Nuxt/Nitro application. Its core is written in Kyo: one runtime owns the request lifecycle, cancellation, streaming, error rendering, configuration and application resources. Write your handlers in Kyo, or in ZIO through a hand-written, first-class adapter over that core. Kyo applications never depend on ZIO; ZIO applications get Kyo through the adapter.

You write ordinary Scala. A handler is a function from an immutable request to the response body. Configuration, storage and shared resources come from Scala APIs. Your models, services and databases stay in your application.

**Status.** The `feat/kyo-core` branch (`0.2.0-SNAPSHOT`) is **not published**. The published [`0.1.0-alpha.1`](https://github.com/yuri4n/h34s/releases/tag/v0.1.0-alpha.1) libraries and [`@yuri4n/h34s-nuxt@0.1.0-alpha.1`](https://github.com/yuri4n/h34s/releases/tag/npm-v0.1.0-alpha.1) have an older API with two independent runtimes. The [migration guide](docs/migration.md) lists every change.

## A Kyo handler

```scala
import dev.yuri4n.exts.kyoh3.*
import kyo.*
import org.getshaka.nativeconverter.NativeConverter

import scala.scalajs.js.annotation.JSExportTopLevel

final case class Greeting(message: String) derives NativeConverter

@JSExportTopLevel("default", "hello-get")
val hello: EventHandler = KHandler.define(_ => Greeting("Hello from Scala"))
```

Mount it with a one-line route file, `server/api/hello.get.ts`:

```ts
export { default } from '../.scala/hello-get.js'
```

`GET /api/hello` answers `{"data":{"message":"Hello from Scala"}}`. Errors are `{"errors":[{"message":"..."}]}`; only `SafeError` data reaches clients. Everything else is an opaque 500, with diagnostics only in Nuxt development mode or debug mode.

## Try the demo

You need JDK 25 (for sbt), sbt 1.11.7, Node 24 and pnpm 10.34.4. From the repository root:

```sh
pnpm install --frozen-lockfile
pnpm run scala:fast
pnpm dev
```

Then, in another terminal:

```sh
curl -H 'content-type: application/json' --data '{"name":"Scala"}' http://127.0.0.1:3000/api/kyo/greeting
```

Expected body: `{"data":{"message":"Hello, Scala!"}}`. Send `{"name":"  "}` to see a 422 with `{"errors":[{"message":"must not be blank","path":"name"}]}`. The Kyo greeting needs no database. The ZIO demo's `/api/test` streams `abc`; its Notes routes need PostgreSQL (see the [development guide](docs/development.md)).

## Guides

| Start | Build | Operate |
| --- | --- | --- |
| [Adoption paths](docs/adoption.md) | [Configuration and secrets](docs/configuration.md) | [Debugging](docs/debugging.md) |
| [Kyo handlers](docs/kyo.md) | [Lifetimes: `Shared`, request scopes](docs/lifetimes.md) | [npm tooling](docs/npm-tooling.md) |
| [ZIO handlers](docs/zio.md) | [Schemas](docs/schema.md) | [Compatibility](docs/compatibility.md) |
| [Architecture](docs/architecture.md) | [PostgreSQL with kyo-sql or Skunk](docs/database.md) | [Releases](docs/releases.md) |
| [Migration from 0.1.0-alpha.1](docs/migration.md) | [Streaming and cancellation](docs/streaming.md) | [Development of this repository](docs/development.md) |
| | [Testing](docs/testing.md) | |
| | [Nitro storage and NuxtHub KV](docs/nuxthub.md) | |

The documentation website in [`website/`](website/README.md) presents the same guides.

## What is here

- `scala-modules/exts/`: the libraries. `h3` (effect-free native layer), `kyo-h3` (the Kyo core), `zio-h3` (the ZIO adapter), the Kyo and ZIO schema bridges, the ZIO converters, and the Kyo and ZIO storage modules. `zio-migrations` is an unpublished experiment.
- `packages/nuxt/`: the npm package `@yuri4n/h34s-nuxt`: build CLI, Nuxt module, development loading, source maps and the Nitro plugin.
- `examples/nuxt/`: an optional demo application. `scala/kyo/` has the Kyo greeting; `scala/zio/` has the ZIO stream and the Skunk Notes example. It is a reference, not a template.
- `website/`: the documentation site, a separate Nuxt app.
- `scripts/`: release, consumer and smoke checks.

h34s does not generate route files, TypeScript types, application scaffolds, database layers or deployment packages yet. Route generation, Nix and sbt-native-packager support are planned.

## Contributing

Use small feature branches, focused commits and reviewable pull requests. [AGENTS.md](AGENTS.md) records the project direction, the user's decisions and the delivery rules.
