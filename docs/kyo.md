# Kyo handlers

Kyo is the h34s core. `KHandler.define` turns a Kyo function into an h3 handler: it takes the request snapshot, runs your program once per request, owns its scope and cancellation, and writes the response once. This guide covers everything a handler does. Your Kyo application never depends on ZIO.

**Status.** This guide describes `0.2.0-alpha.1`. The published `0.1.0-alpha.1` has a different Kyo API (see the [migration guide](migration.md)). See [releases](releases.md) for installation.

## Prerequisites

- Scala 3.9.0 and Scala.js 1.22.0. Kyo 1.0.0-RC7 needs both, and its macros need **JDK 25** to run sbt. Java is a build tool only; your handlers run in Node.
- Node 24, `h3@1.15.4` as a runtime dependency of your Nuxt app, and the `@yuri4n/h34s-nuxt` module. See [npm tooling](npm-tooling.md).
- The library dependency `"dev.yuri4n" %%% "kyo-h3-ext" % "<version>"`. Registry access is in [releases.md](releases.md).

Import the API with `import dev.yuri4n.exts.kyoh3.*` and Kyo with `import kyo.*`. The `kyoh3` package re-exports the shared values (`Request`, `ResponseHead`, `SafeError`, `Secret`, ...).

## Write your first handler

A handler is a function from the request snapshot to the response body:

```scala
import dev.yuri4n.exts.kyoh3.*
import kyo.*
import org.getshaka.nativeconverter.NativeConverter

import scala.scalajs.js.annotation.JSExportTopLevel

final case class Greeting(message: String) derives NativeConverter

@JSExportTopLevel("default", "hello-get")
val hello: EventHandler = KHandler.define: request =>
    Greeting(s"Hello from ${request.path}")
```

The response is `200` with `{"data":{"message":"Hello from <path>"}}`. The function runs once per request, never when the module loads.

Export each handler as the `default` export of its own Scala.js module, as above. Then mount it with a one-line route file in your Nuxt app, for example `server/api/hello.get.ts`:

```ts
export { default } from '../.scala/hello-get.js'
```

This route file is the one piece of JavaScript you still write. Generated route registration is planned.

## Read the request

`Request` is an immutable snapshot, taken once when the handler starts:

| Member | Value |
| --- | --- |
| `method`, `path` | Upper-case method; path and query as h3 reports them |
| `header(name)`, `headers` | Case-insensitive lookup; duplicate headers joined with `", "` |
| `headerValues(name)` | Every value of a header, in request order |
| `query`, `queryAll` | First value per key; all values per key |
| `routerParam(name)`, `routerParams` | Decoded router parameters |
| `cookie(name)`, `cookies` | Parsed cookies |
| `bearerToken` | The token of exactly one `Authorization: Bearer <token>` header, else `None` |
| `development`, `debug` | Set only by the h34s Nitro plugin; a request cannot set them |
| `body` | The one-shot request body |

`request.param(name)` reads a required router parameter. It fails with a 400 `HttpError` when the parameter is absent.

Read the body with `request.body.json[A]` or `request.body.text()`. The first read fixes the outcome; a second read returns the same text.

| Situation | Result |
| --- | --- |
| `Content-Type` is neither absent nor JSON (`application/json`, `application/*+json`); with `HandlerOptions(requireJsonContentType = true)`, also when it is absent | `HttpError` 415 |
| Body larger than the limit (checked from `Content-Length`, then while reading) | `HttpError` 413; h34s never buffers more than the limit |
| Text is not valid JSON | `HttpError` 400 |
| JSON does not match the converter's shape | `InvalidInput` 422 with a generic message |
| A schema-backed converter rejects the value | `InvalidInput` 422 with your schema's messages; see [schemas](schema.md) |
| Any other exception from the converter | A defect: opaque 500 |

The default limit is `BodyLimit.default` (1 MiB). Change it for one handler with `HandlerOptions(bodyLimit = ...)`, or for one read with `request.body.json[A](BodyLimit(bytes))`. An absent body is `""` for `text()`. For `json[A]`, an absent or empty body decodes like JSON `null`: a model answers 422, and `json[Option[A]]` gives `None`.

### Cross-site requests

A browser sends no `Content-Type` for a cross-site `fetch` with a byte body or for `navigator.sendBeacon`, and such requests need no CORS preflight. By default `json[A]` accepts a body without a content type, so a cookie-authenticated route that changes state can be reached from another site when the session cookie allows it. For those routes:

- Keep session cookies `SameSite=Lax` (the `SetCookie` default) or `Strict`.
- Set `HandlerOptions(requireJsonContentType = true)`: a JSON read without a content type answers 415. `$fetch` and `curl --json` send `application/json`.
- Check `request.header("sec-fetch-site")` or `request.header("origin")` on routes that change state.

## Return a body

What your function returns decides the response body:

| Return type | Response |
| --- | --- |
| Any `A` with a `NativeConverter` | 200 (or the status you set) with `{"data": <value>}` |
| `Unit` | 204 with no body, unless you set another status |
| `kyo.Stream[String, S]` | The strings, written as UTF-8 text as they come (`text/plain; charset=utf-8` unless you set a content type); see [streaming](streaming.md) |

`NativeConverter` converts directly between Scala and JavaScript values. No JSON text is made on the way; h3 writes the HTTP body.

## Set status, headers and cookies

Status, headers and cookies are local state of your program, a `Var[ResponseHead]`. The `Response` operations change that state; h34s writes the head once, after your program and its finalizers finish. `GreetingRequest` here is a request model like the demo's:

```scala
val created: EventHandler = KHandler.define: request =>
    for
      input <- request.body.json[GreetingRequest]
      _     <- Response.status(Status.Created)
      _     <- Response.header("x-request-id", "abc", OnFailure.Keep)
      _     <- Response.cookie(SetCookie("session", "abc", maxAge = Some(3600)))
    yield Greeting(s"Hello, ${input.name}!")
```

| Operation | Effect |
| --- | --- |
| `Response.status(status)` | Last write wins. Use `Status.Created` or `Status.unsafe(code)` for literals |
| `Response.header(name, value)` | Replaces earlier values of that header |
| `Response.appendHeader(name, value)` | Adds another value |
| `Response.cookie(SetCookie(...))` | `Set-Cookie`. Defaults: `Path=/`, `HttpOnly`, `Secure`, `SameSite=Lax` |
| `Response.deleteCookie(name, path, domain, secure, onFailure)` | Expires a cookie. `path` (default `/`) and `domain` must match the cookie; `Secure` is sent by default, which browsers need to delete a `__Host-` or `__Secure-` cookie |
| `Response.redirect(location, status)` | A 3xx status (default `Status.Found`) and `Location` |
| `Response.head` | What your program has set so far, without the handler defaults |

An invalid header name or value, cookie name, or a non-3xx redirect status is a programming error. The check runs when the operation runs, not when you build the value; the handler then fails, and h34s answers with a 500.

## Fail safely

Fail with a `SafeError` through Kyo's typed channel, `Abort.fail`. Only these errors reach the client:

```scala
final case class NoteNotFound(id: String) extends SafeError:
    def message: String     = "Note not found"
    override def status: Int = 404
```

| Failure | Status | Body (default options) |
| --- | --- | --- |
| `Abort.fail(safeError)` | `safeError.status` (400 by default) | `{"errors":[{"message":"..."}]}` |
| `KHandler.failWith(status, message)` (`Nothing < Abort[HttpError]`) | `status` | the same shape |
| `InvalidInput` from a body read | 422 | one entry per schema message, with `path` when known, never input values |
| Anything else: thrown exceptions, panics, a `SafeError` that is thrown | 500 | `{"errors":[{"message":"Internal server error"}]}` |

`HttpError(status, message)` is a ready-made `SafeError`. Helpers: `HttpError.badRequest`, `unauthorized`, `notFound`, `conflict`, `unprocessable`. Add a header with `.header(name, value)`, for example `WWW-Authenticate` on a 401. A `SafeError` whose status is outside 400-599 is treated as a defect.

The failure rule protects the response. On failure, the status always comes from the error, so a 2xx never leaks. The handler defaults, the entries you marked `OnFailure.Keep`, and the error's own headers survive. Everything else your program set is discarded.

Never put secrets, stack traces or input values in a `SafeError` message. Clients see it in production.

## Choose the wire format

Pass `HandlerOptions` to `KHandler.define(options)(program)`:

| Option | Default | Alternatives |
| --- | --- | --- |
| `success` | `SuccessEnvelope.Data`: `{"data": value}` | `SuccessEnvelope.Raw`: the converted value without the envelope. A string is sent as `text/plain; charset=utf-8` and any other value as JSON (`application/json`), unless you set a content type; `X-Content-Type-Options: nosniff` comes with the default. A `null` or `undefined` value becomes 204 |
| `errors` | `ErrorEnvelope.List`: always `{"errors":[...]}` | `ErrorEnvelope.SingleOrList`: `{"error":{...}}` for one entry, `{"errors":[...]}` for several |
| `defaults` | `ResponseHead.empty` | For example `ResponseHead.noStore`: `Cache-Control: no-store` on success and failure |
| `bodyLimit` | `BodyLimit.default` (1 MiB) | Any `BodyLimit(bytes)` |
| `requireJsonContentType` | `false`: `json[A]` accepts a body without a content type | `true`: 415 for such a body; see [cross-site requests](#cross-site-requests) |

Streams and `Unit` bodies are never wrapped.

## Compose services in your AppHandler

Write one wiring point that provides your services to every handler. The demo calls it `AppHandler`; you choose the name. Define your service first:

```scala
trait Greetings:
    def greet(name: String): Greeting < Sync
```

Then provide it around each handler function. This is the demo's `AppHandler` (`examples/nuxt/scala/kyo/.../AppHandler.scala`):

```scala
object AppHandler:
    /** Every response is `Cache-Control: no-store`; bodies use the default `{ "data": ... }` envelope. */
    private val options = HandlerOptions(defaults = ResponseHead.noStore)

    def define[B: ResponseBody](
        program: Request => B < (HandlerEffects & Env[Greetings]),
    )(using Frame): EventHandler =
      KHandler.define(options)(request => Services.greetings.get.map(greetings => Env.run(greetings)(program(request))))
```

`Services.greetings` is a `Shared[Greetings]` built from the startup runtime config; see [lifetimes](lifetimes.md#application-resources).

Endpoints then ask for the service:

```scala
@JSExportTopLevel("default", "kyo-greeting-post")
val personalizedGreeting: EventHandler = AppHandler.define: request =>
    for
      input    <- request.body.json[GreetingRequest]
      greeting <- Env.use[Greetings](_.greet(input.name))
    yield greeting
```

`HandlerEffects` is `Async & Scope & Abort[Throwable] & Var[ResponseHead]`: the effects the core handles. Resolve every other effect, such as `Env[Greetings]`, before you pass the program to `KHandler.define`.

`Env.run` provides a value. It does not acquire or release anything. For a database pool or another resource that lives as long as the application, use `Shared`; see [lifetimes](lifetimes.md). Read configuration with `RuntimeConfig`; see [configuration](configuration.md).

## Kyo details to keep in mind

These come from Kyo 1.0.0-RC7 semantics. They matter once your handlers grow:

- **`Abort.run[Throwable]` also catches thrown exceptions.** If your own code runs `Abort.run[Throwable]` (or recovers `Throwable`), a thrown exception becomes a typed failure there. h34s itself keeps thrown exceptions as defects. Recover narrower types, such as your own `SafeError` subtypes.
- **`Abort.recover` does not roll back `Var` changes.** A status or header you set before a recovered failure stays set. Set response entries after the risky step, or set them again in the recovery branch.
- **`Var` needs an isolate across forks.** Forking operations such as `Fiber.init` and `Async.race` require an `Isolate` for a `Var` they carry, so code that forks while it needs `Var[ResponseHead]` does not compile without one. Call `Response.*` in the request fiber, before or after the parallel part.
- **Kyo `Scope` finalizer errors are logged and do not change the response.** Do work that must affect the response in the main program, not in a finalizer.
- **`Async.uninterruptible` does not make the request wait.** It runs the step on a fiber that nothing owns. In a handler, use `KHandler.uninterruptible(step)`; see [lifetimes](lifetimes.md#when-the-client-disconnects).

## Choose locale data in your build

Kyo brings a full currency database through scala-java-locales. It adds tens of MB to the linked JavaScript. The demo excludes it in `build.sbt`, because its English-only greeting does not need currency data:

```scala
excludeDependencies += ExclusionRule("io.github.cquiroz", "locales-full-currencies-db_sjs1_3")
```

This is application policy. Keep the database if you format currencies or other locales.

## Run the demo's Kyo routes

The demo in `examples/nuxt/` has a Kyo greeting with no database. From the repository root, with JDK 25 selected for sbt:

```sh
pnpm install --frozen-lockfile
pnpm run scala:fast
pnpm dev
```

Then, in another terminal:

```sh
curl http://127.0.0.1:3000/api/kyo/greeting
curl -H 'content-type: application/json' --data '{"name":"Scala"}' http://127.0.0.1:3000/api/kyo/greeting
curl -H 'content-type: application/json' --data '{"name":"  "}' http://127.0.0.1:3000/api/kyo/greeting
```

Expected bodies: `{"data":{"message":"Hello, Kyo!"}}`, `{"data":{"message":"Hello, Scala!"}}`, and a 422 with `{"errors":[{"message":"must not be blank","path":"name"}]}`. The salutation comes from `runtimeConfig.greeting.salutation`; start the server with `NUXT_GREETING_SALUTATION=Hi` to change it.

**Evidence.** On macOS x86_64 with Node 24.19.0, these responses came back from a production build of the demo (`nuxt build`, then the Node server), and under `nuxt dev` (Nuxt 4.3.1, after `pnpm run scala:fast`): GET 200 with `{data}` and `no-store`, a blank name 422 with path `name`. See [compatibility](compatibility.md).

## Next steps

- [Configuration and secrets](configuration.md)
- [Application and request lifetimes](lifetimes.md)
- [Schemas](schema.md) for validated models
- [Databases](database.md) with kyo-sql
- [Streaming and cancellation](streaming.md)
- [Testing](testing.md) handlers without h3
- [Debugging](debugging.md) and source maps
