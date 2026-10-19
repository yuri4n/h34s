# ZIO handlers

The ZIO API is a first-class, hand-written adapter over the Kyo core. You write ordinary ZIO programs: `for` comprehensions, `ZIO.service`, typed errors, `ZLayer` and `ZStream`. `ZHandler.define` runs them on a ZIO `Runtime` and hands the result to the core, which owns the request lifecycle, the wire format and the response.

Your application depends on `zio-h3-ext`, which brings the Kyo core with it. You never write Kyo code in a ZIO application.

**Status.** This guide describes `0.2.0-alpha.1`. The older `0.1.0-alpha.1` has a different ZIO API. The [migration guide](migration.md) maps every old call to its replacement.

## Prerequisites

- Scala 3.9.0, Scala.js 1.22.0, and JDK 25 to run sbt. The ZIO modules share the Kyo core's toolchain.
- ZIO 2.1.26. kyo-zio 1.0.0-RC7 pins this version; keep your own ZIO dependencies on it.
- `"dev.yuri4n" %%% "zio-h3-ext" % "<version>"`, plus `h3@1.15.4` and `@yuri4n/h34s-nuxt` in your Nuxt app.

Import the API with `import dev.yuri4n.exts.zioh3.*`. It re-exports the shared values (`Request`, `ResponseHead`, `SafeError`, `Secret`, ...).

## Write your first handler

A handler is a function from the request snapshot to a ZIO program that returns the body. This is the demo's database-free route (`examples/nuxt/scala/zio/.../TestHandler.scala`):

```scala
import dev.yuri4n.exts.zioh3.{EventHandler, ZHandler}
import zio.ZIO
import zio.stream.ZStream

import scala.scalajs.js.annotation.JSExportTopLevel

@JSExportTopLevel("default", "test")
val handler: EventHandler = ZHandler.define(_ => ZIO.succeed(ZStream("a", "b", "c")))
```

The response body is `abc`. Mount the export with a one-line route file, `server/api/test.ts`:

```ts
export { default } from '../.scala/test.js'
```

## Keep handler functions small and testable

The demo keeps each handler as a plain value, so a test can call it with a fake request (`examples/nuxt/scala/zio/.../NotesHandler.scala`):

```scala
object NotesHandler:
    val list: Request => ZIO[Notes, Throwable, List[NoteView]] =
      _ => ZIO.serviceWithZIO[Notes](_.list).map(_.map(NoteView.from))

    /** The schema validates and trims the input before any database work; the handler adds no
      * validation code.
      */
    val create: Request => ZIO[HandlerEnv & Notes, Throwable, NoteView] = request =>
      for
        input <- request.body.json[CreateNote]
        note  <- ZIO.serviceWithZIO[Notes](_.create(input.text))
        _     <- Response.status(Status.Created)
      yield NoteView.from(note)

@JSExportTopLevel("default", "notes-get")
val listNotes: EventHandler = AppHandler.define(NotesHandler.list)

@JSExportTopLevel("default", "notes-post")
val createNote: EventHandler = AppHandler.define(NotesHandler.create)
```

`Notes`, `Note` (the domain note), `NoteView` (the JSON it sends), `CreateNote` (the JSON it reads) and `AppHandler` are demo code. Define your own.

## Read the request

The `Request` snapshot is the same value the Kyo core uses; see [Kyo handlers](kyo.md#read-the-request) for every member and for the body status codes. The ZIO signatures:

| Call | Type |
| --- | --- |
| `request.body.json[A]` | `IO[HttpError \| InvalidInput, A]` |
| `request.body.json[A](limit)` | the same, with an explicit `BodyLimit` |
| `request.body.text()` | `IO[HttpError, String]` |
| `request.param(name)` | `IO[HttpError, String]`; 400 when absent |

## Return a body

| Return type | Response |
| --- | --- |
| Any `A` with a `NativeConverter` | 200 (or the status you set) with `{"data": <value>}` |
| `Unit` | 204 with no body, unless you set another status |
| `ZStream[Any, E, String]` with `E <: Throwable` | The strings, written as UTF-8 text as they come (`text/plain; charset=utf-8` unless you set a content type); see [streaming](streaming.md) |

## Set status, headers and cookies

`Response` has the same operations as the Kyo API, as `URIO[ZState[ResponseHead], Unit]`: `status`, `header`, `appendHeader`, `cookie`, `deleteCookie(name, path, domain, secure, onFailure)`, `redirect`, and `head`. h34s writes the head once, after your program and its finalizers finish. The failure rule is the same: on failure, the status comes from the error, and only handler defaults, `OnFailure.Keep` entries and the error's headers survive.

Two ZIO details:

- `ZState` is backed by a `FiberRef`. A forked fiber works on its own copy of the head; joining it copies the child's head back into the parent (ZIO's default `FiberRef` join). Set response entries in the request fiber when you can.
- `catchAll` and other recoveries do not roll back head changes.

An invalid header name or value, cookie name, or redirect status makes the effect die, and h34s answers with a 500.

## Fail safely

| Failure | Status | Body (default options) |
| --- | --- | --- |
| `ZIO.fail(safeError)` | `safeError.status` | `{"errors":[{"message":"..."}]}` |
| `ZHandler.failWith(status, msg, msgs*)` | `status` | one entry per message |
| `InvalidInput` from a body read | 422 | one entry per schema message, with `path` when known |
| Any other typed failure, a defect, interruption, or several failures | 500 | `{"errors":[{"message":"Internal server error"}]}` |

`ZHandler.failWith` fails with `ErrorResponse(errors, status)`, as in `0.1.0-alpha.1`. `ErrorResponse` is not a `SafeError` (its `errors` field is a `NonEmptyList[String]`), so `catchSome { case e: SafeError => ... }` does not catch it; h34s approves both at the boundary. A `SafeError` or `ErrorResponse` stays approved even when sibling fibers were interrupted with it (for example in `zipPar`). Any other `Throwable` in the error channel is not approved: catching an exception and failing with it does not make it public. Map it to your own `SafeError` instead.

<!-- claude 2026-10-19: changed: a dying finalizer is logged and keeps the response, as in Kyo (issue #40) -->
A ZIO finalizer that dies does not change the response, as in Kyo: h34s logs `h34s: a ZIO finalizer failed` with ZIO's `Cause.prettyPrint` on the server, and the client gets the value, the `Unit` response or the safe error that your program produced. Do work that must affect the response in the program, not in a finalizer. In development or debug mode, the diagnostics of a failed program show ZIO's full `Cause.prettyPrint`, with ZIO traces and Scala source lines.

## Choose the wire format

`ZHandler.define(options)` takes the same `HandlerOptions` as the Kyo core:

```scala
ZHandler.define(HandlerOptions(defaults = ResponseHead.noStore)): request =>
  ...
```

See [Kyo handlers](kyo.md#choose-the-wire-format) for the envelopes, defaults, body limit and `requireJsonContentType` (see [cross-site requests](kyo.md#cross-site-requests)).

## Compose services in your AppHandler

Write one wiring point that provides your layers. This is the demo's (`examples/nuxt/scala/zio/.../AppHandler.scala`):

```scala
object AppHandler:
    /** Every response is `Cache-Control: no-store`; bodies use the default `{ "data": ... }` envelope. */
    private val options = HandlerOptions(defaults = ResponseHead.noStore)

    /** One Skunk pool for all requests: built on first use, released when Nitro closes. */
    private val database = ZShared.layer(SessionPool.layer)

    def define[B: ZResponseBody](program: Request => ZIO[HandlerEnv & Notes, Throwable, B]): EventHandler =
      ZHandler.define(options): request =>
          database.provide(program(request).provideSomeLayer[HandlerEnv & SessionPool](NotesLive.layer))
```

`HandlerEnv` is `ZState[ResponseHead] & Scope`: what h34s supplies to every ZIO handler. The `Scope` is the request scope. It stays open while a streamed body is sent.

Use ordinary `provide`, `provideSomeLayer` and `ZLayer` composition. A `ZLayer` value is rebuilt each time you provide it. For a resource that all requests share, such as a pool, wrap the layer in `ZShared`; see [lifetimes](lifetimes.md).

`ZHandler.define` runs on `Runtime.default` unless a `given Runtime[Any]` is in scope. You do not need to create one.

## Type aliases

| Alias | Meaning |
| --- | --- |
| `HandlerEnv` | `ZState[ResponseHead] & Scope` |
| `ZHandler[R, E, A]` | `ZIO[HandlerEnv & R, E, A]` |
| `Handler[E, A]`, `TaskHandler[A]`, `URHandler[R, A]`, `UHandler[A]`, `RHandler[R, A]` | The usual ZIO shapes over `HandlerEnv` |
| `TaskStream[A]`, `RStream[R, A]`, `URStream[R, A]` | `ZStream` shapes |

## Run the demo's ZIO routes

`/api/test` needs no database. Run the demo as in [Kyo handlers](kyo.md#run-the-demos-kyo-routes), then:

```sh
curl http://127.0.0.1:3000/api/test
```

The body is `abc`. The Notes routes need PostgreSQL; see [databases](database.md) and the [development guide](development.md).

## Next steps

[Configuration](configuration.md), [lifetimes](lifetimes.md), [schemas](schema.md), [databases](database.md), [streaming](streaming.md), [testing](testing.md), and [debugging](debugging.md).
