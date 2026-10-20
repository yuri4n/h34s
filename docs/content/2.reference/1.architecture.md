# Architecture: a Kyo core with a ZIO adapter

h34s is a toolbox for running Scala.js server code in your Nuxt/Nitro application. You add the modules you need, write your models and services, and mount your handlers as Nuxt routes. The repository also holds a runnable demo in `examples/nuxt/` and the documentation site in `website/`. Neither is part of the toolbox.

**Status.** This page describes the design of `0.2.0-alpha.1`. The older `0.1.0-alpha.1` release has a different module graph with two independent runtimes; the [migration guide](migration.md) lists every change.

## Layers

One runtime does the hard work. The h3 transport, the request lifecycle, cancellation, streaming, error rendering, configuration access and application-scoped resources are implemented once, in Kyo. The ZIO API is a hand-written adapter over that core.

| Layer | sbt project / artifact | What it gives you | Depends on |
| --- | --- | --- | --- |
| Native layer | `h3Ext` / `h3-ext` | Pure values shared by every runtime: `EventHandler`, `Request`, `RequestBody`, `BodyLimit`, `ResponseHead`, `Status`, `SetCookie`, `OnFailure`, `HandlerOptions`, `SafeError`, `HttpError`, `InvalidInput`, `ErrorEntry`, `ConfigError`, `Secret`, `NativeConverter[Unit]`. The h3, Node and Nitro facades are internal. | native-converter. No effect library. |
| Kyo core | `kyoH3Ext` / `kyo-h3-ext` | `KHandler.define`, the request lifecycle, body reading with limits, `Response`, error rendering, diagnostics, `RuntimeConfig`, `Shared`, Kyo streams | `h3-ext`, kyo-core 1.0.0-RC7 |
| ZIO adapter | `zioH3Ext` / `zio-h3-ext` | `ZHandler.define`, `Response` over `ZState`, `ZStream` bodies, `ZRuntimeConfig`, `ZShared`, and the awaiting ZIO-to-Kyo bridge | `kyo-h3-ext`, kyo-zio, ZIO 2.1.26, zio-prelude, zio-json, `native-converter-zio-ext` |
| Kyo Schema bridge | `nativeConverterKyoSchemaExt` / `native-converter-kyo-schema-ext` | `derives Schema, NativeSchema` for Kyo Schema | `h3-ext`, kyo-schema |
| ZIO Schema bridge | `nativeConverterZIOSchemaExt` / `native-converter-zio-schema-ext` | `derives Schema, NativeSchema` for ZIO Schema | `h3-ext`, zio-schema 1.7.5 |
| ZIO converters | `nativeConverterZIOExt`, `nativeConverterZIOJsonExt` | `NonEmptyList` and zio-json `Json` converters, `eimap` | `h3-ext`, ZIO |
| Storage | `kyoNuxtHubExt` / `kyo-nuxthub-ext`, `zioNuxtHubExt` / `zio-nuxthub-ext` | `KHubKv.mount`, `ZHubKv.mount` over Nitro storage | Kyo core; the ZIO one also needs the ZIO adapter |
| npm tooling | `@yuri4n/h34s-nuxt` | Build CLI, Nuxt module, development loading, source maps, and the Nitro plugin that supplies flags, runtime config, storage and the close hook | Node, Nuxt. No Scala dependency. |
| Your application | your sbt project and Nuxt app | Models, services, `AppHandler`, resources, route mounts, database and deployment | the modules you choose |

Dependencies point one way. Kyo modules never depend on ZIO. ZIO modules get Kyo transitively.

```text
h3-ext                                 (no effect library)
├── kyo-h3-ext                         (Kyo core)
│   ├── kyo-nuxthub-ext
│   └── zio-h3-ext                     (also uses native-converter-zio-ext, kyo-zio, ZIO)
│       └── zio-nuxthub-ext            (also uses kyo-nuxthub-ext)
├── native-converter-kyo-schema-ext    (also uses kyo-schema)
├── native-converter-zio-schema-ext    (also uses zio-schema)
└── native-converter-zio-ext           (also uses ZIO)
    └── native-converter-zio-json-ext
```

Each module depends on its parent. A build check during the refactor found no `dev.zio` artifact on the Kyo core's compile classpath.

Pick the smallest set for your application:

- A Kyo application adds `kyo-h3-ext`. Add `native-converter-kyo-schema-ext` for schemas and `kyo-nuxthub-ext` for storage.
- A ZIO application adds `zio-h3-ext`. Add `native-converter-zio-schema-ext` for schemas and `zio-nuxthub-ext` for storage.
- Every application adds the npm package `@yuri4n/h34s-nuxt` and the runtime dependency `h3@1.15.4`.

## Follow one request

1. Nitro routes the request to a linked Scala.js module. The h34s Nuxt module registered that module for the route that your handler declares with `.at(Route...)`; `h34s-scala` read the route after linking and wrote it into the output's route manifest. A one-line route file in `server/api/` that re-exports the module works too.
2. The h34s Nitro plugin has attached a frozen record to `event.context.h34s`: the development and debug flags, the request's runtime config, and Nitro storage. Requests cannot change this record.
3. `KHandler.define` takes one immutable `Request` snapshot. It runs your function `Request => B < HandlerEffects` once, on its own fiber, in a request `Scope`, with a `Var[ResponseHead]` that starts empty.
4. Your program reads the body, calls your services, sets status, headers and cookies with `Response.*`, and returns the body.
5. When the program and its finalizers have finished, the core writes the response once. On success it applies the handler defaults, then everything the program set. On failure it applies the [failure rule](kyo.md#fail-safely). It renders the body with the handler's `HandlerOptions`.
6. If the client disconnects first, the core interrupts the program, waits for its cleanup, and writes nothing.

`ZHandler.define` adds one step. It runs your ZIO program in one ZIO fiber per request, on the given ZIO `Runtime`, with a `ZState[ResponseHead]` and a ZIO `Scope`. It then hands the result and the head to the core, which does steps 5 and 6. Interrupting a request interrupts the ZIO fiber and waits for its finalizers.

## Where your code goes

The functional-design layers map to code like this:

| Layer | What lives there | Who owns it |
| --- | --- | --- |
| Business logic | Your services and programs, as Kyo `A < S` or ZIO effects | You |
| Interfaces | Your service traits (`Greetings`, `Notes`) and the h34s values (`Request`, `SafeError`, `Secret`) | You and h34s |
| Implementation | The h34s runtime, its internal facades, and your service interpreters (`NotesLive`) | h34s and you |
| Application | Your `AppHandler`: configuration, then `Shared`/`ZShared` resources, then `Env`/`ZLayer` wiring | You |

`AppHandler` is a name the demo uses for this one wiring point. It is your code, not an h34s export. Call `KHandler.define` or `ZHandler.define` directly when you do not need a wrapper.

## Errors and diagnostics

A handler is an error boundary. Only a `SafeError` that your program fails with through the typed channel reaches the client, with its status and entries. Everything else becomes an opaque 500. Diagnostics appear only in Nuxt development mode or in debug mode. See [Kyo handlers](kyo.md#fail-safely), [ZIO handlers](zio.md#fail-safely) and [debugging](debugging.md).

<!-- claude 2026-10-19: changed: both runtimes now treat a failing finalizer the same (issue #40) -->
A finalizer that fails behaves the same in both runtimes: the failure is logged on the server, and the response that the program computed does not change. The log messages differ. Kyo 1.0.0-RC7 logs `Scope finalizer failed` through its `Log`. The ZIO adapter writes `h34s: a ZIO finalizer failed`, with `Cause.prettyPrint`, through `console.error`, for value, `Unit` and streamed bodies and on a client disconnect. Since the next release (provisionally `0.3.0-alpha.1`), a dying ZIO finalizer no longer turns a value or `Unit` response into a 500.

## What h34s does not do

- Infer TypeScript types from Scala classes. The generated declarations describe what each converter writes; a converter that does not describe itself gives `unknown`. See [routes and typed `$fetch`](routes.md).
- Supply a database client, a connection pool or migrations. Use kyo-sql or Skunk directly; see [database](database.md).
- Package or deploy your application. Nix and sbt-native-packager support are planned.
- Run anywhere but Node. The core needs a Node HTTP response for streaming. Other Nitro presets are untested.

## Next coherent slices

1. Generated route registration and `.d.ts` emission, so a new endpoint needs no TypeScript file.
2. The planned Nix and sbt-native-packager paths over the same build pipeline.
3. Interactive documentation: compiler-derived types, symbol links and runnable examples.
