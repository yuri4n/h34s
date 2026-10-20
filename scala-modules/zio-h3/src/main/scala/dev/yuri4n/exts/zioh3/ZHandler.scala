package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.kyoh3.{KHandler, ResponseBody}
import dev.yuri4n.exts.zioh3.internal.ZioRequest
import zio.prelude.NonEmptyList
import zio.{IO, Runtime, ZIO}

// claude 2026-10-19: changed: streams are sent with ZHandler.sendStream
// claude 2026-10-20: changed: streams are sent with Response.sendStream; ZHandler.sendStream is deleted (ADR-0004, Q8)
/** Defines h3 handlers from ZIO programs.
  *
  * A handler is a function from the request snapshot to a ZIO program that returns the response
  * body: `Request => ZIO[HandlerEnv, ErrorResponse | Throwable, B]`. h34s runs it once per request on
  * the given ZIO `Runtime`, supplies `HandlerEnv` (response head state, request scope and the
  * transport), and writes the response once, after the program and its finalizers finish. A
  * handler that streams sends the head and the stream itself with `Response.sendStream` and returns
  * its `Sent`.
  *
  * Provide your own services before you pass the program, with ordinary ZIO (`provide`,
  * `provideSomeLayer`, `ZShared.provide`). The wire format is the one of `KHandler`: a value body is
  * `{"data": ...}`, a `Unit` body is a 204 without a body, a sent `ZStream` is UTF-8 text, and errors
  * are `{"errors": [{"message": ...}]}`.
  */
object ZHandler:
    /** The program receives the request snapshot and returns the body. Uses `HandlerOptions.default`.
      * Pass a `Runtime` as a given to run the program on it (default: `Runtime.default`).
      */
    def define[B](program: Request => ZIO[HandlerEnv, ErrorResponse | Throwable, B])(using
        body: ZResponseBody[B],
        runtime: Runtime[Any] = Runtime.default,
    ): EventHandler =
      Configured(HandlerOptions.default)(program)

    /** Same, with options for every request: envelopes, response defaults (applied on success and
      * on failure, for example `ResponseHead.noStore`) and the default body limit.
      *
      * {{{
      * ZHandler.define(HandlerOptions(defaults = ResponseHead.noStore)): request =>
      *   ...
      * }}}
      */
    def define(options: HandlerOptions): Configured = Configured(options)

    /** Fails with approved messages and a status. Each message becomes one error entry. */
    def failWith(status: Int, msg: String, msgs: String*): IO[ErrorResponse, Nothing] =
      ZIO.fail(ErrorResponse(NonEmptyList(msg, msgs*), status))

    /** Handlers that share one set of options. */
    final class Configured private[ZHandler] (options: HandlerOptions):
        def apply[B](program: Request => ZIO[HandlerEnv, ErrorResponse | Throwable, B])(using
            body: ZResponseBody[B],
            runtime: Runtime[Any] = Runtime.default,
        ): EventHandler =
          given ResponseBody[B] = ZResponseBody.core(body, runtime)
          KHandler.define[B](options)(request => ZioRequest.run(runtime)(program(request)))
end ZHandler
