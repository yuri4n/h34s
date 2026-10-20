package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.h3.H3Fixtures
import dev.yuri4n.exts.h3.H3Fixtures.{FakeRequest, FakeResponse}
import dev.yuri4n.exts.h3.internal.H3Event
import kyo.*
import munit.Assertions.assertEquals

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js

/** Shared helpers: invoke native handlers over real h3 events and run Kyo programs in tests. */
private[exts] object KyoTesting:
    given ExecutionContext = ExecutionContext.global

    /** One request: a fake Node request/response pair behind a real h3 event. */
    final class Call(
        path: String = "/test",
        method: String = "GET",
        headers: Seq[(String, String)] = Nil,
        development: js.Any = js.undefined,
        withSocket: Boolean = true,
        record: Option[js.Object] = None,
        debug: js.Any = js.undefined,
    ):
        val request: FakeRequest   = FakeRequest(method, path, headers)
        val response: FakeResponse = FakeResponse(withSocket)
        val event: H3Event =
          val flags =
            if js.isUndefined(development) && js.isUndefined(debug) then None
            else Some(H3Fixtures.development(development, debug))
          H3Fixtures.event(request, response, record.orElse(flags))

        def handled: Boolean = event.handled

        def invoke(handler: EventHandler): Future[js.Any] =
          handler.toNative.asInstanceOf[js.Function1[js.Any, js.Promise[js.Any]]](event).toFuture
    end Call

    def json(body: js.Any): String = js.JSON.stringify(body)

    val opaque: String = "{\"errors\":[{\"message\":\"Internal server error\"}]}"

    def assertOpaque(body: js.Any): Unit = assertEquals(json(body), opaque)

    /** The value inside the default `{ "data": ... }` envelope. */
    def data(body: js.Any): js.Dynamic = body.asInstanceOf[js.Dynamic].data

    /** The error body for approved messages in the default `{ "errors": [...] }` envelope. */
    def errors(messages: String*): String =
      json(js.Dynamic.literal(errors = js.Array(messages.map(message => js.Dynamic.literal(message = message))*)))

    /** Runs a Kyo program to a Future of its typed result. */
    def run[A](program: => A < (Async & Abort[Throwable]))(using Frame): Future[Result[Throwable, A]] =
      import AllowUnsafe.embrace.danger
      Fiber.Unsafe.init(Abort.run[Throwable](program)).toFuture()

    // claude 2026-10-19: created: runs a handler program without h3: a detached transport and no $fetch
    /** Provides what `KHandler.define` provides per request, for calling a handler function directly. */
    def detached[A](program: A < HandlerEffects)(using Frame): A < (Async & Scope & Abort[Throwable] & Var[ResponseHead]) =
      Env.run(Fetch.unavailable)(Env.run(HandlerTransport.detached)(program))

    // claude 2026-10-20: created: captures console.error lines (the core's defect log) while `body` runs
    // claude 2026-10-20: changed: delegates to consoleErrorsSeen
    /** The `console.error` lines written while `body` runs. munit runs the tests of a suite one at a
      * time, so one capture is active at a time.
      */
    def consoleErrors[A](body: => Future[A]): Future[(A, List[String])] = consoleErrorsSeen(_ => body)

    // claude 2026-10-20: created: the capture of consoleErrors, with the lines so far readable by the body (to wait for a log line)
    /** Like `consoleErrors`, but `body` can read the lines captured so far, to wait for a log line. */
    def consoleErrorsSeen[A](body: (() => List[String]) => Future[A]): Future[(A, List[String])] =
      val console  = js.Dynamic.global.console
      val original = console.error
      val lines    = scala.collection.mutable.ListBuffer.empty[String]
      val capture: js.Function2[js.Any, js.Any, Unit] =
        (first, second) => lines += List(first, second).filterNot(js.isUndefined).map(String.valueOf).mkString(" ")
      console.error = capture
      val running =
        try body(() => lines.toList)
        catch case error: Throwable => Future.failed(error)
      running.transform { outcome =>
        console.error = original
        outcome.map(value => (value, lines.toList))
      }

    def nextTurn: Future[Unit] =
      val completed = Promise[Unit]()
      js.timers.setTimeout(0) { completed.success(()); () }
      completed.future

    /** A few event-loop turns, for checks that something did NOT happen. */
    def turns(count: Int): Future[Unit] =
      if count <= 0 then Future.unit else nextTurn.flatMap(_ => turns(count - 1))

    // claude 2026-10-20: created: waits for something that does happen; the number of turns it takes is not fixed
    /** Waits one event-loop turn at a time until `done` holds, for at most `limit` turns; the
      * assertions after it report a condition that never held. Kyo's JS scheduler runs fibers
      * through `setImmediate` with time slices, so the number of `setTimeout` turns that an
      * asynchronous step takes depends on load: use this, not `turns`, to wait for something that
      * does happen.
      */
    def turnsUntil(limit: Int)(done: => Boolean): Future[Unit] =
      if limit <= 0 || done then Future.unit else nextTurn.flatMap(_ => turnsUntil(limit - 1)(done))
end KyoTesting
