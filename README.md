# h34s

Write Nuxt server routes in Scala.js.

h34s is a toolbox for your Nuxt/Nitro application. Its core is written in Kyo: one runtime owns the request lifecycle, cancellation, streaming, error rendering, configuration and application resources. Write your handlers in Kyo, or in ZIO through a hand-written, first-class adapter over that core. Kyo applications never depend on ZIO; ZIO applications get Kyo through the adapter.

You write ordinary Scala. A handler is a function from an immutable request to the response body, and it declares its own route. Configuration, storage and shared resources come from Scala APIs. Your models, services and databases stay in your application.

<!-- claude 2026-10-20: changed: the guides' new paths under docs/content/ (issue #25) -->

**Status: [`0.2.0-alpha.1` is published](https://github.com/yuri4n/h34s/releases/tag/v0.2.0-alpha.1).** The nine `dev.yuri4n` libraries are in private GitHub Packages, and the matching build tooling is published as [`@yuri4n/h34s-nuxt@0.2.0-alpha.1`](https://github.com/yuri4n/h34s/releases/tag/npm-v0.2.0-alpha.1). Both release workflows verified the packages through the authenticated registry. See the [installation and release guide](docs/content/2.reference/2.releases.md) and the [release notes](.github/release-notes/0.2.0-alpha.1.md). The older [`0.1.0-alpha.1`](https://github.com/yuri4n/h34s/releases/tag/v0.1.0-alpha.1) libraries and [`@yuri4n/h34s-nuxt@0.1.0-alpha.1`](https://github.com/yuri4n/h34s/releases/tag/npm-v0.1.0-alpha.1) stay published, with an older API and two independent runtimes. The [migration guide](docs/content/2.reference/4.migration.md) lists every change.

## A Kyo handler

```scala
import dev.yuri4n.exts.kyoh3.*
import kyo.*
import org.getshaka.nativeconverter.NativeConverter

import scala.scalajs.js.annotation.JSExportTopLevel

final case class Greeting(message: String) derives NativeConverter

@JSExportTopLevel("default", "hello-get")
val hello: EventHandler = KHandler.define(_ => Greeting("Hello from Scala")).at(Route.get("/api/hello"))
```

<!-- claude 2026-10-20: changed: the guides' new paths under docs/content/ (issue #25) -->

After `h34s-scala` links it, the h34s Nuxt module mounts the route; you write no route file. Nuxt types `$fetch('/api/hello')` from the declaration that `h34s-scala` generates next to the linked module (see [routes and typed `$fetch`](docs/content/1.guide/02.routes.md)). `GET /api/hello` answers `{"data":{"message":"Hello from Scala"}}`. Errors are `{"errors":[{"message":"..."}]}`; only `SafeError` data reaches clients. Everything else is an opaque 500, with diagnostics only in Nuxt development mode or debug mode.

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

<!-- claude 2026-10-20: created: the "Set up with a coding assistant" entry in the Start column -->

<!-- claude 2026-10-20: changed: a Getting started row and the guides' new paths under docs/content/ (issue #25) -->

| Start | Build | Operate |
| --- | --- | --- |
| [Getting started](docs/content/1.guide/01.getting-started.md) | [Configuration and secrets](docs/content/1.guide/05.configuration.md) | [Debugging](docs/content/1.guide/12.debugging.md) |
| [Adoption paths](docs/content/1.guide/02.adoption.md) | [Lifetimes: `Shared`, request scopes](docs/content/1.guide/06.lifetimes.md) | [npm tooling](docs/content/1.guide/13.npm-tooling.md) |
| [Kyo handlers](docs/content/1.guide/03.kyo.md) | | |
| [Routes and typed `$fetch`](docs/content/1.guide/02.routes.md) | [Schemas](docs/content/1.guide/07.schema.md) | [Compatibility](docs/content/2.reference/3.compatibility.md) |
| [ZIO handlers](docs/content/1.guide/04.zio.md) | [PostgreSQL with kyo-sql or Skunk](docs/content/1.guide/08.database.md) | [Releases](docs/content/2.reference/2.releases.md) |
| [Architecture](docs/content/2.reference/1.architecture.md) | [Streaming and cancellation](docs/content/1.guide/09.streaming.md) | [Development of this repository](docs/development.md) |
| [Migration from 0.1.0-alpha.1](docs/content/2.reference/4.migration.md) | [Testing](docs/content/1.guide/10.testing.md) | |
| [Set up with a coding assistant](docs/content/1.guide/14.agent-setup.md) | [Nitro storage and NuxtHub KV](docs/content/1.guide/11.storage.md) | |

<!-- claude 2026-10-20: changed: the guides' new paths under docs/content/ (issue #25) -->

The guides live in [`docs/`](docs/README.md), under `docs/content/`. The [documentation site](https://h34s.yuri4n.dev/) renders the same files.

## What is here

<!-- claude 2026-10-20: changed: the guides' new paths under docs/content/ (issue #25); the libraries in scala-modules/ (#23) and the zio-migrations example in examples/ (#24) -->

- `scala-modules/`: the libraries. `h3` (effect-free native layer), `kyo-h3` (the Kyo core), `zio-h3` (the ZIO adapter), the Kyo and ZIO schema bridges, the ZIO converters, and the Kyo and ZIO storage modules.
- `packages/nuxt/`: the npm package `@yuri4n/h34s-nuxt`: build CLI (linking, route manifests and TypeScript declarations), Nuxt module (route registration), development loading, source maps and the Nitro plugin.
- `examples/nuxt/`: an optional demo application. `scala/kyo/` has the Kyo greeting; `scala/zio/` has the ZIO stream and the Skunk Notes example. It is a reference, not a template.
- `examples/zio-migrations/`: an unpublished example that derives Skunk codecs and PostgreSQL DDL from ZIO Schema. It is not a supported API.
- `docs/`: the guides and reference pages (`docs/content/`), the documentation site that renders them (a separate Nuxt app), and notes for maintainers.
- `scripts/`: release, consumer and smoke checks.

h34s mounts your Scala routes and types their response bodies. It does not generate request-body types, application scaffolds, database layers or deployment packages. Nix and sbt-native-packager support are planned.

## Contributing

Use small feature branches, focused commits and reviewable pull requests. [AGENTS.md](AGENTS.md) records the project direction, the user's decisions and the delivery rules.
