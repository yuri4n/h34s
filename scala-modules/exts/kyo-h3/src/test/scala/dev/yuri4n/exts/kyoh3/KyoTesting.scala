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
    ):
        val request: FakeRequest   = FakeRequest(method, path, headers)
        val response: FakeResponse = FakeResponse(withSocket)
        val event: H3Event =
          val context = record.orElse(if js.isUndefined(development) then None else Some(H3Fixtures.development(development)))
          H3Fixtures.event(request, response, context)

        def handled: Boolean = event.handled

        def invoke(handler: EventHandler): Future[js.Any] =
          handler.toNative.asInstanceOf[js.Function1[js.Any, js.Promise[js.Any]]](event).toFuture
    end Call

    def json(body: js.Any): String = js.JSON.stringify(body)

    def assertOpaque(body: js.Any): Unit =
      assertEquals(json(body), "{\"errors\":[\"Internal server error\"]}")

    /** Runs a Kyo program to a Future of its typed result. */
    def run[A](program: => A < (Async & Abort[Throwable]))(using Frame): Future[Result[Throwable, A]] =
      import AllowUnsafe.embrace.danger
      Fiber.Unsafe.init(Abort.run[Throwable](program)).toFuture()

    def nextTurn: Future[Unit] =
      val completed = Promise[Unit]()
      js.timers.setTimeout(0) { completed.success(()); () }
      completed.future

    /** A few event-loop turns, for checks that something did NOT happen. */
    def turns(count: Int): Future[Unit] =
      if count <= 0 then Future.unit else nextTurn.flatMap(_ => turns(count - 1))
end KyoTesting
