package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.h3.internal.HeaderRules
import dev.yuri4n.exts.kyoh3.ResponseBody
import dev.yuri4n.exts.kyoh3.internal.{EventStreams, StreamResponse}
import dev.yuri4n.exts.zioh3.internal.{KyoCall, ZStreamSource}
import zio.stream.ZStream
import zio.{Cause, Ref, URIO, ZIO, ZState}

// claude 2026-10-20: changed: sendStream joins the head operations (ADR-0004, Q8), and sendEvents (ADR-0007)
/** Response head operations over ZIO's `ZState[ResponseHead]`. They change local state only; h34s
  * writes the head once, after the handler finishes. A failed handler keeps only the entries
  * marked `OnFailure.Keep` and takes its status from the error. `sendStream` is the one operation
  * that writes: it sends the head and a stream, and its `Sent` result ends the handler;
  * `sendEvents` is `sendStream` for server-sent events.
  *
  * `ZState` is backed by a `FiberRef`: a forked fiber works on its own copy, and joining it copies
  * the child's head back into the parent (ZIO's default `FiberRef` join). `catchAll` and other
  * recoveries do not roll changes back.
  *
  * Invalid header names, header values, cookie names or redirect statuses are programming errors:
  * the effect dies, which h34s reports as an opaque 500.
  */
object Response:
    def status(status: Status): URIO[ZState[ResponseHead], Unit] =
      ZIO.updateState[ResponseHead](_.withStatus(status))

    /** Sets a header, replacing earlier values. */
    def header(name: String, value: String, onFailure: OnFailure = OnFailure.Discard): URIO[ZState[ResponseHead], Unit] =
      ZIO.suspendSucceed {
        HeaderRules.requireHeader(name, value)
        ZIO.updateState[ResponseHead](_.withHeader(name, value, onFailure))
      }

    /** Adds a header value, keeping earlier values. */
    def appendHeader(name: String, value: String, onFailure: OnFailure = OnFailure.Discard)
        : URIO[ZState[ResponseHead], Unit] =
      ZIO.suspendSucceed {
        HeaderRules.requireHeader(name, value)
        ZIO.updateState[ResponseHead](_.appendHeader(name, value, onFailure))
      }

    def cookie(cookie: SetCookie, onFailure: OnFailure = OnFailure.Discard): URIO[ZState[ResponseHead], Unit] =
      ZIO.suspendSucceed {
        HeaderRules.requireCookieName(cookie.name)
        ZIO.updateState[ResponseHead](_.withCookie(cookie, onFailure))
      }

    /** Expires a cookie. `path` and `domain` must match the cookie that was set; `secure` sends the
      * `Secure` attribute, which browsers require to delete a `__Secure-` or `__Host-` cookie.
      */
    def deleteCookie(
        name: String,
        path: Option[String] = Some("/"),
        domain: Option[String] = None,
        secure: Boolean = true,
        onFailure: OnFailure = OnFailure.Discard,
    ): URIO[ZState[ResponseHead], Unit] =
      ZIO.suspendSucceed {
        HeaderRules.requireCookieName(name)
        ZIO.updateState[ResponseHead](_.withoutCookie(name, path, domain, secure, onFailure))
      }

    /** Sets a 3xx status and the `Location` header. */
    def redirect(location: String, status: Status = Status.Found): URIO[ZState[ResponseHead], Unit] =
      ZIO.suspendSucceed {
        require(status.code >= 300 && status.code <= 399, s"Redirect status must be 3xx, got ${status.code}")
        HeaderRules.requireHeader("location", location)
        ZIO.updateState[ResponseHead](_.redirect(location, status))
      }

    /** The head that the handler has set so far (defaults from `HandlerOptions` are not included). */
    def head: URIO[ZState[ResponseHead], ResponseHead] = ZIO.getState[ResponseHead]

    // claude 2026-10-19: created: ZHandler.sendStream, the ZIO way to stream (decision 1); its Sent value is the handler's result
    // claude 2026-10-20: changed: moved from ZHandler to Response, one name in both runtimes (ADR-0004, Q8); the head is written at the call (Q1); a stream failure fails this effect with the stream's own cause (Q3)
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

    // claude 2026-10-20: created: sendEvents, server-sent events over sendStream, the twin of the Kyo operation (ADR-0007)
    /** Sends the head set so far as a server-sent event stream, then one frame per event, and gives
      * `Sent`. Make it the handler's last step: return its `Sent` as the body.
      *
      * It sets the headers of h3's `createEventStream`, as `Response.header` would:
      * `content-type: text/event-stream`, `cache-control: private, no-cache, no-store,
      * no-transform, must-revalidate, max-age=0`, `x-accel-buffering: no` and, except over HTTP/2,
      * `connection: keep-alive`. They replace a value of the same name that you or the handler
      * defaults set; your other headers, cookies and status stay. Keep the status 200: a browser's
      * `EventSource` fails on any other status. Then it is `sendStream` over the frames
      * (`ServerEvent.frame`), with the same rules: the head leaves at the call, the request `Scope`
      * stays open until the stream ends or the client goes away, a disconnect waits for the
      * stream's finalizers, and a failure after the head ends the response and fails this effect
      * with the stream's own cause.
      *
      * {{{
      * Response.sendEvents(ZStream(3, 2, 1).map(n => ServerEvent.json(Tick(n), event = Some("tick"))))
      * }}}
      */
    def sendEvents[E <: Throwable](events: ZStream[Any, E, ServerEvent]): ZIO[ZState[ResponseHead] & HandlerTransport, Throwable, Sent] =
      ZIO.serviceWithZIO[HandlerTransport](transport => ZIO.updateState[ResponseHead](EventStreams.head(transport, _))) *>
        sendStream(events.map(ServerEvent.frame))
end Response
