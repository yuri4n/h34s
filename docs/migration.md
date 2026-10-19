# Migrate from 0.1.0-alpha.1

The Kyo core refactor changes the module graph, the handler shape and the wire format. This guide lists every removed or renamed API with its replacement. Work through it in order: toolchain, dependencies, handlers, clients.

**Status.** The new API is [`0.2.0-alpha.1`, which is published](https://github.com/yuri4n/h34s/releases/tag/v0.2.0-alpha.1). The `0.1.0-alpha.1` artifacts stay published and unchanged, so you can upgrade when you are ready.

## 1. Update the toolchain

| | 0.1.0-alpha.1 | Now |
| --- | --- | --- |
| Scala | 3.5.2 for native and ZIO modules, 3.9.0 for Kyo | 3.9.0 for every module |
| Build JDK | 21 was enough for ZIO-only builds | 25 for every build (Kyo 1.0.0-RC7 macros) |
| ZIO | 2.1.23 | 2.1.26 (pinned by kyo-zio 1.0.0-RC7) |
| zio-json | as resolved | 0.7.44 |
| Kyo | 1.0.0-RC7 | 1.0.0-RC7 |
| Scala.js, NativeConverter, h3 | 1.22.0, 0.9.0, 1.15.4 | unchanged |

Set `scalaVersion := "3.9.0"` and run sbt with JDK 25. Production still runs on Node only.

## 2. Update dependencies

| Module | Change |
| --- | --- |
| `zio-js-ext` | **Removed.** It was an empty package. Delete the dependency |
| `nuxthub-ext` | **Removed.** `HubKv` and `NativeKvSetOptions` are gone; storage comes from `KHubKv.mount` / `ZHubKv.mount` |
| `zio-h3-schema-ext` | **Removed.** Use `native-converter-zio-schema-ext` with `request.body.json[A]` (step 3) |
| `native-converter-kyo-schema-ext` | **New.** Kyo Schema bridge: `derives Schema, NativeSchema` |
| `zio-h3-ext` | Now depends on `kyo-h3-ext` and kyo-zio. ZIO applications get Kyo transitively |
| `zio-nuxthub-ext` | Now depends on `kyo-nuxthub-ext` and `zio-h3-ext` |
| `native-converter-zio-ext`, `native-converter-zio-schema-ext` | Now depend on `h3-ext` |
| `h3-ext` | No longer exposes ScalablyTyped-generated h3 facades. The facades are internal |

A ZIO application now pulls Kyo's dependency graph, including scala-java-locales' full currency database. If you do not need currency data, exclude it as described in [Kyo handlers](kyo.md#choose-locale-data-in-your-build).

## 3. Rewrite handlers

The handler is now a function from an immutable `Request` snapshot to the body. There is no event object in the environment, and no API changes the native response while your program runs.

### Kyo

| 0.1.0-alpha.1 | Now |
| --- | --- |
| `KHandler.define(program)` with a by-name `A < HandlerEffects` | `KHandler.define(request => program)` |
| `KHandler.define[A: NativeConverter]` | `KHandler.define[B: ResponseBody]`; any `NativeConverter` value, `Unit` and `kyo.Stream[String, S]` are bodies |
| `HandlerEffects` with `Env[KH3Event]` | `HandlerEffects = Async & Scope & Abort[Throwable] & Var[ResponseHead]` |
| `KH3Event`, `KHandler.getEvent`, `rawEvent` | **Removed.** Use the `Request` argument |
| `KHandler.readBody[A]`, `KH3Event.readBody[A]` | `request.body.json[A]` |
| `KHandler.setResponseStatus(code)`, `KH3Event.setResponseStatus` | `Response.status(Status.Created)` or `Response.status(Status.unsafe(code))` |
| `dev.yuri4n.exts.kyoh3.SafeError` | Same name, now defined in `h3-ext` and re-exported. It adds `headers` and `errors` |
| `KHubKv(native)` | `KHubKv.mount(base)` |
| `CommonEvHandler` | `EventHandler` (the old alias is deprecated) |

Before and after:

```scala
// 0.1.0-alpha.1
val personalizedGreeting = AppHandler.define:
    for
      input    <- KHandler.readBody[GreetingRequest]
      greeting <- Env.use[Greetings](_.greet(input.name))
    yield greeting

// Now
val personalizedGreeting: EventHandler = AppHandler.define: request =>
    for
      input    <- request.body.json[GreetingRequest]
      greeting <- Env.use[Greetings](_.greet(input.name))
    yield greeting
```

Your `AppHandler.define` now takes `Request => B < (HandlerEffects & ...)` and calls `KHandler.define(options)(request => ...)`. See [Kyo handlers](kyo.md#compose-services-in-your-apphandler).

### ZIO

| 0.1.0-alpha.1 | Now |
| --- | --- |
| `ZHandler.define(program)` with `ZIO[SH3Event & Scope & R, E, A]` | `ZHandler.define(request => program)` with `ZIO[HandlerEnv, ErrorResponse \| Throwable, B]` |
| `SH3Event`, `ZHandler.getEvent` | **Removed.** Use the `Request` argument; `HandlerEnv = ZState[ResponseHead] & Scope` |
| `ZHandler[R, E, A]`, `Handler`, `TaskHandler`, `URHandler`, `UHandler`, `RHandler` | Same names, now over `HandlerEnv` instead of `SH3Event` |
| `ZHandler.readBody[A]`, `readBody[A](strict)`, `SH3Event.readBody` | `request.body.json[A]`. Parsing is always strict; a non-JSON content type is a 415 |
| `ZHandler.getQuery`, `getQuery[T](using Conversion)` | `request.query`, `request.queryAll` |
| `ZHandler.getRouterParams`, `getRouterParam(name)` | `request.routerParams`, `request.routerParam(name)`, or `request.param(name)` (400 when absent) |
| `GetRouterParamsError`, `SH3Event.MissingRouterParamError` | **Removed.** A missing parameter is `HttpError(400, "Missing router parameter: <name>")` |
| `SH3Event.parseCookies` | `request.cookies`, `request.cookie(name)` |
| `ZHandler.setResponseStatus(code)` | `Response.status(...)` |
| `ZHandler.sendStream(stream)`, `SH3Event.sendStream` | Return the `ZStream` as the body. Elements are strings now: `sendStream` converted each element with its `NativeConverter`, which Node cannot write. Encode values yourself, for example `stream.map(_.toJson + "\n")` |
| `respondWith`, `respondWithZIO` | **Removed.** Return the body; set the head with `Response.*` |
| `ZHandler.$fetch`, `SH3Event.$fetch` | **Removed.** Call your service directly, or use an HTTP client |
| `mkHandler`, top-level `defineEventHandler(h)` and `defineEventHandler(runtime, h)` | `ZHandler.define`. Put a `given Runtime[Any]` in scope for a custom runtime |
| `ZIOH3.Console` | **Removed.** Use ZIO's `Console` or `ZIO.log` |
| `taskStreamToReadableStream`, `taskStreamToReadableStreamReader`, `streamconversion` | **Removed.** Streams are bodies now |
| `fromNative_` extension on `js.Any` | **Removed.** Decode request bodies with `request.body.json[A]` |
| `ErrorResponse(errors)` | `ErrorResponse(errors, status = 400)`. A one-field pattern match needs `_`; its `JsonEncoder` output now has `status` |
| ZIO `SafeError` with `val message`, `status`, `maybeUnderlying` | The shared `SafeError` (`message`, `status`, `headers`, `errors`). `maybeUnderlying` is gone; a `val message` still works |
| `ZHubKv(native)` | `ZHubKv.mount(base)` |
| `ZHandler.readBodyWithSchema[A]`, `InvalidBody` (zio-h3-schema) | `derives Schema, NativeSchema` on the model, then `request.body.json[A]`; failures are `InvalidInput` |
| `NativeSchema(schema).converter` returned a converter that failed with a generic error | It now throws `InvalidInput` with entries; `Codec` gains `validate`; `derives NativeSchema` is new |

Before and after, from the demo's Notes route:

```scala
// 0.1.0-alpha.1
val createNote = AppHandler.define:
    for
      input <- ZHandler.readBodyWithSchema[CreateNote]
      note  <- ZIO.serviceWithZIO[Notes](_.create(input.text))
      _     <- ZHandler.setResponseStatus(201)
    yield note

// Now
val create: Request => ZIO[HandlerEnv & Notes, Throwable, Note] = request =>
  for
    input <- request.body.json[CreateNote]
    note  <- ZIO.serviceWithZIO[Notes](_.create(input.text))
    _     <- Response.status(Status.Created)
  yield note

val createNote: EventHandler = AppHandler.define(create)
```

`CreateNote` now uses `derives NativeSchema` with its ZIO Schema in the companion. See [schemas](schema.md).

## 4. Update your clients

The wire format changed. Update your `fetch` code, and remove the TypeScript interfaces you wrote for these bodies: the generated declarations type `$fetch` (see [routes and typed `$fetch`](routes.md)):

| Response | 0.1.0-alpha.1 | Now (default options) |
| --- | --- | --- |
| A value | The value as is | `{"data": <value>}` |
| `Unit` | `undefined` (h3 decided the status) | 204 with no body |
| A stream | Through `sendStream` | The stream of strings as the body, as UTF-8 text; `text/plain; charset=utf-8` unless you set a content type |
| A safe error | `{"errors": ["message"]}` | `{"errors": [{"message": "..."}]}`, with `path` for schema entries |
| An internal error | `{"errors": ["Internal server error"]}`, plus top-level `debug` in development | `{"errors": [{"message": "Internal server error"}]}`; in development or debug mode the entry carries `debug.cause` |

To keep the old success shape for a handler, pass `HandlerOptions(success = SuccessEnvelope.Raw)`. A `Raw` string is sent as `text/plain; charset=utf-8` and any other value as JSON text with `application/json`, unless you set a content type; h3 no longer guesses (it sent strings as `text/html`). There is no option for the old string-array errors.

Body problems now have their own statuses: 415 for a non-JSON content type, 413 for a body above the limit (1 MiB by default), 400 for invalid JSON, and 422 for a body that does not match the converter or schema. The old schema reader answered 422 for invalid JSON and `Request body does not match schema` for every rejection. A plain `readBody` failure used to be an opaque 500.

## 5. Move configuration and storage into Scala

- Read Nuxt runtime config with `RuntimeConfig` / `ZRuntimeConfig` instead of reading environment variables yourself. The demo moved from Ciris and `MY_APP_DB_*` to `runtimeConfig.database` and `NUXT_DATABASE_*`. See [configuration](configuration.md).
- Replace application-side `@JSImport("@nuxthub/kv", "kv")` objects with `KHubKv.mount(base)` or `ZHubKv.mount(base)`. See [Nitro storage](nuxthub.md).
- Replace per-request database connections with an application pool in `Shared` or `ZShared`. See [lifetimes](lifetimes.md).

## 6. Check the Nuxt module

Keep `@yuri4n/h34s-nuxt` in `modules`. Its Nitro plugin is now `runtime/h34s.mjs` and supplies the development and debug flags, runtime config, storage and the close hook. The module registers it for you. If you registered `runtime/development.mjs` yourself, remove that entry; the old file still re-exports the new plugin for one release.

The module also declares `runtimeConfig.h34s.debug: false`, so `NUXT_H34S_DEBUG=true` turns on diagnostics at runtime. Nuxt's own `debug` option does not turn them on. Keep debug mode off in production; see [debugging](debugging.md).

<!-- claude 2026-10-19: changed: merged main's wording with the route-generation additions -->
Mount your handlers from Scala, then delete the route files:

1. Add `.at(Route.get("/api/notes"))` (with the route and method of the file) to each exported handler.
2. Remove `entries` from `h34s.config.mjs`, or keep them for outputs that still have route files.
3. Link with `h34s-scala dev`. It prints the routes it found and writes `h34s-routes.json` and the `.d.ts` files.
4. Delete the `server/api` files that re-export the linked modules, and the hand-written `declare module` types for them. A route file and `.at` for the same route register the handler twice.

Route files keep working if you want to move later. See [routes and typed `$fetch`](routes.md).

Use the package version that matches your Scala libraries: `@yuri4n/h34s-nuxt@0.2.0-alpha.1`. The `0.1.0-alpha.1` package lacks the new plugin records, and `RuntimeConfig` and `KHubKv.mount` then fail with `ConfigError`.

## Roll back

To stay on the old API, keep your dependencies at `0.1.0-alpha.1`, Scala 3.5.2 for ZIO code, and `@yuri4n/h34s-nuxt@0.1.0-alpha.1`. Both stay published. Do not mix modules from the two versions in one application: their `SafeError` types and module graphs differ.
