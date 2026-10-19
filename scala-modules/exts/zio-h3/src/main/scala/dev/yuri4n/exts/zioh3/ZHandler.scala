package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.kyoh3.internal.StreamResponse
import dev.yuri4n.exts.kyoh3.{KHandler, ResponseBody}
import dev.yuri4n.exts.zioh3.internal.{KyoCall, ZStreamSource, ZioRequest}
import zio.prelude.NonEmptyList
import zio.stream.ZStream
import zio.{IO, Runtime, ZIO, ZState}

// claude 2026-10-19: changed: streams are sent with ZHandler.sendStream
/** Defines h3 handlers from ZIO programs.
  *
  * A handler is a function from the request snapshot to a ZIO program that returns the response
  * body: `Request => ZIO[HandlerEnv, ErrorResponse | Throwable, B]`. h34s runs it once per request on
  * the given ZIO `Runtime`, supplies `HandlerEnv` (response head state, request scope and the
  * transport), and writes the response once, after the program and its finalizers finish. A
  * handler that streams sends the head and the stream itself with `ZHandler.sendStream` and returns
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

    // claude 2026-10-19: created: ZHandler.sendStream, the ZIO way to stream (decision 1); its Sent value is the handler's result
    /** Sends the head set so far and then `stream`, and gives `Sent`. Make it the handler's last
      * step: return its `Sent` as the body.
      *
      * Each string is written as UTF-8 text, as is. The head gets `text/plain; charset=utf-8` unless
      * you set a content type, and the `x-h34s-stream` marker. The request `Scope` stays open until
      * the stream ends or the client cancels it; a disconnect interrupts the stream and waits for its
      * finalizers. A failure before anything reached the client fails this effect, and h34s answers
      * with the failure rule; after that, the failure is logged and the response is ended.
      */
    def sendStream[E <: Throwable](stream: ZStream[Any, E, String]): ZIO[ZState[ResponseHead] & HandlerTransport, Throwable, Sent] =
      for
        head <- ZIO.getState[ResponseHead]
        transport <- ZIO.service[HandlerTransport]
        runtime <- ZIO.runtime[Any]
        sent <- KyoCall.run(StreamResponse.send(transport, head, ZStreamSource(runtime, stream, ResponseBody.text)))
      yield sent

    /** Handlers that share one set of options. */
    final class Configured private[ZHandler] (options: HandlerOptions):
        def apply[B](program: Request => ZIO[HandlerEnv, ErrorResponse | Throwable, B])(using
            body: ZResponseBody[B],
            runtime: Runtime[Any] = Runtime.default,
        ): EventHandler =
          given ResponseBody[B] = ZResponseBody.core(body, runtime)
          KHandler.define[B](options)(request => ZioRequest.run(runtime)(program(request)))
end ZHandler
