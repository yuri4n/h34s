package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.h3.internal.HeaderRules
import dev.yuri4n.exts.kyoh3.ResponseBody.StreamEffects
import dev.yuri4n.exts.kyoh3.internal.{EventStreams, StreamResponse}
import kyo.{Env, Frame, Stream, Sync, Var, `<`}

// claude 2026-10-20: changed: the Scaladoc names sendEvents (ADR-0007)
/** Response head operations. They change local state only; the core writes the head once, after
  * the handler finishes. `Abort.recover` does not roll these changes back. `sendStream` is the one
  * operation that writes: it sends the head and a stream, and its `Sent` result ends the handler;
  * `sendEvents` is `sendStream` for server-sent events.
  *
  * Invalid header names, header values, cookie names or redirect statuses are programming errors.
  * The check runs when the operation runs, not when you build the value: the handler then fails
  * with an `IllegalArgumentException`, which the core reports as a defect (opaque 500).
  */
object Response:
    def status(status: Status)(using Frame): Unit < Var[ResponseHead] =
      Var.updateDiscard[ResponseHead](_.withStatus(status))

    /** Sets a header, replacing earlier values. */
    def header(name: String, value: String, onFailure: OnFailure = OnFailure.Discard)(using Frame): Unit < Var[ResponseHead] =
      checked(HeaderRules.requireHeader(name, value))(_.withHeader(name, value, onFailure))

    /** Adds a header value, keeping earlier values. */
    def appendHeader(name: String, value: String, onFailure: OnFailure = OnFailure.Discard)(using
        Frame,
    ): Unit < Var[ResponseHead] =
      checked(HeaderRules.requireHeader(name, value))(_.appendHeader(name, value, onFailure))

    def cookie(cookie: SetCookie, onFailure: OnFailure = OnFailure.Discard)(using Frame): Unit < Var[ResponseHead] =
      checked(HeaderRules.requireCookieName(cookie.name))(_.withCookie(cookie, onFailure))

    /** Expires a cookie. `path` and `domain` must match the cookie that was set; `secure` sends the
      * `Secure` attribute, which browsers require to delete a `__Secure-` or `__Host-` cookie.
      */
    def deleteCookie(
        name: String,
        path: Option[String] = Some("/"),
        domain: Option[String] = None,
        secure: Boolean = true,
        onFailure: OnFailure = OnFailure.Discard,
    )(using Frame): Unit < Var[ResponseHead] =
      checked(HeaderRules.requireCookieName(name))(_.withoutCookie(name, path, domain, secure, onFailure))

    /** Sets a 3xx status and the `Location` header. */
    def redirect(location: String, status: Status = Status.Found)(using Frame): Unit < Var[ResponseHead] =
      checked {
        require(status.code >= 300 && status.code <= 399, s"Redirect status must be 3xx, got ${status.code}")
        HeaderRules.requireHeader("location", location)
      }(_.redirect(location, status))

    // claude 2026-10-19: created: sendStream, the way to stream (decision 1); its Sent value is the handler's result
    // claude 2026-10-20: changed: the head is written at the call (ADR-0004, Q1); a stream failure fails this step (Q3), seen by a Scope.run in the handler, not by the request scope
    /** Sends the head set so far and then `stream`, and gives `Sent`. Make it the handler's last
      * step: return its `Sent` as the body.
      *
      * The head is written when you call `sendStream`; over a Node socket it is also flushed, so the
      * client has the status and the headers before the first element. After the call the status is
      * fixed, so validate input and acquire resources first. The head gets
      * `text/plain; charset=utf-8` unless you set a content type, and the `x-h34s-stream` marker.
      * Each string is written as UTF-8 text, as is.
      *
      * The request scope stays open until the stream ends or the client cancels it; a disconnect
      * interrupts the stream's producer and waits for its finalizers. A failure of the stream ends
      * the response: h34s closes the connection, so the client cannot take the partial body for a
      * complete one, and this step fails with the stream's error. Your program, and a `Scope.run` or
      * a transaction around this step, see the failure and can roll back. A finalizer that you add
      * to the request scope itself gets `Absent`, because h34s closes that scope after the program,
      * without its outcome: write the `Scope.run` inside the handler. The core logs the failure once
      * and writes nothing more.
      *
      * The stream may use at most `Async & Abort[Throwable] & Scope`: it runs in its own fiber.
      */
    def sendStream[S >: StreamEffects](stream: Stream[String, S])(using Frame): Sent < HandlerEffects =
      Var.use[ResponseHead](head =>
        Env.use[HandlerTransport](transport => StreamResponse.send(transport, head, ResponseBody.chunks(stream)(ResponseBody.text))),
      )

    // claude 2026-10-20: created: sendEvents, server-sent events over sendStream (ADR-0007)
    /** Sends the head set so far as a server-sent event stream, then one frame per event, and gives
      * `Sent`. Make it the handler's last step: return its `Sent` as the body.
      *
      * It sets the headers of h3's `createEventStream`, as `Response.header` would:
      * `content-type: text/event-stream`, `cache-control: private, no-cache, no-store,
      * no-transform, must-revalidate, max-age=0`, `x-accel-buffering: no` and, except over HTTP/2,
      * `connection: keep-alive`. They replace a value of the same name that you or the handler
      * defaults set; your other headers, cookies and status stay. Keep the status 200: a browser's
      * `EventSource` fails on any other status. Then it is `sendStream` over the frames
      * (`ServerEvent.frame`), with the same rules: the head leaves at the call, the request scope
      * stays open until the stream ends or the client goes away, a disconnect waits for the
      * stream's finalizers, and a failure after the head ends the response and fails this step.
      *
      * {{{
      * Response.sendEvents(Stream.init(Seq(3, 2, 1)).map(n => ServerEvent.json(Tick(n), event = Some("tick"))))
      * }}}
      *
      * The events may use at most `Async & Abort[Throwable] & Scope`, as in `sendStream`.
      */
    def sendEvents[S >: StreamEffects](events: Stream[ServerEvent, S])(using Frame): Sent < HandlerEffects =
      Env.use[HandlerTransport](transport => Var.updateDiscard[ResponseHead](EventStreams.head(transport, _)))
        .andThen(sendStream(events.map(event => Sync.defer(ServerEvent.frame(event)))))

    /** The head that the handler has set so far (defaults passed to `define` are not included). */
    def head(using Frame): ResponseHead < Var[ResponseHead] = Var.get[ResponseHead]

    /** Runs `check` inside the program's continuation, where the core's `Abort.run` sees a throw as a
      * defect. (The function given to `Var.update` runs in the Var handler, outside that boundary.)
      */
    private inline def checked(inline check: => Unit)(inline change: ResponseHead => ResponseHead)(using
        Frame,
    ): Unit < Var[ResponseHead] =
      Var.use[ResponseHead] { head =>
        check
        Var.setDiscard(change(head))
      }
end Response
