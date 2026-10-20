package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.kyoh3.internal.StreamResponse
import dev.yuri4n.exts.kyoh3.{KHandler, ResponseBody}
import dev.yuri4n.exts.zioh3.internal.{KyoCall, ZStreamSource, ZioRequest}
import zio.prelude.NonEmptyList
import zio.stream.ZStream
import zio.{Cause, IO, Ref, Runtime, ZIO, ZState}

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
    // claude 2026-10-20: changed: the head is written at the call (ADR-0004, Q1); a stream failure fails this effect with the stream's own cause (Q3)
    /** Sends the head set so far and then `stream`, and gives `Sent`. Make it the handler's last
      * step: return its `Sent` as the body.
      *
      * The head is written when this effect runs; over a Node socket it is also flushed, so the
      * client has the status and the headers before the first element. After that the status is
      * fixed, so validate input and acquire resources first. The head gets
      * `text/plain; charset=utf-8` unless you set a content type, and the `x-h34s-stream` marker.
      * Each string is written as UTF-8 text, as is.
      *
      * The request `Scope` stays open until the stream ends or the client cancels it; a disconnect
      * interrupts the stream and waits for its finalizers. A failure of the stream ends the
      * response: h34s closes the connection, so the client cannot take the partial body for a
      * complete one, and this effect fails with the stream's own cause. Your program, a
      * `ZIO.scoped` block around this effect and the request `Scope` see the failure and can roll
      * back. h34s logs it once and writes nothing more.
      */
    def sendStream[E <: Throwable](stream: ZStream[Any, E, String]): ZIO[ZState[ResponseHead] & HandlerTransport, Throwable, Sent] =
      for
        head <- ZIO.getState[ResponseHead]
        transport <- ZIO.service[HandlerTransport]
        runtime <- ZIO.runtime[Any]
        failure <- Ref.make(Option.empty[Cause[E]])
        // The core carries the stream's failure as a Throwable; keep the stream's own cause, so the
        // program sees its typed error. An interruption is a cancel, not a failure of the stream.
        recorded = stream.tapErrorCause(cause => failure.set(Some(cause)).unless(cause.isInterruptedOnly))
        sent <- KyoCall.run(StreamResponse.send(transport, head, ZStreamSource(runtime, recorded, ResponseBody.text)))
          .catchAllCause(cause => failure.get.flatMap(own => ZIO.failCause(own.getOrElse(cause))))
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
