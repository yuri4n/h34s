package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.h3.H3Fixtures
import dev.yuri4n.exts.h3.H3Fixtures.{FakeRequest, FakeResponse}
import dev.yuri4n.exts.h3.internal.H3Event
import zio.*

import scala.scalajs.js

/** ZIO helpers: invoke native handlers over real h3 events (the h3-ext fixtures). */
private[exts] object ZioTesting:
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

        /** Calls the handler and waits for the Promise it returns. */
        def invoke(handler: EventHandler): Task[js.Any] =
          ZIO.fromPromiseJS(handler.toNative.asInstanceOf[js.Function1[js.Any, js.Promise[js.Any]]](event))

        /** Sends a JSON body with a Content-Length, then ends the request stream. */
        def body(text: String): Call =
          request.send(text)
          request.finish()
          this
    end Call

    /** A POST call with a JSON body. */
    def post(body: String, contentType: String = "application/json", development: js.Any = js.undefined): Call =
      Call(
        method = "POST",
        headers = Seq("content-type" -> contentType, "content-length" -> body.getBytes("UTF-8").length.toString),
        development = development,
      ).body(body)

    def wire(body: js.Any): String = js.JSON.stringify(body)

    val opaque: String = "{\"errors\":[{\"message\":\"Internal server error\"}]}"

    /** The value inside the default `{ "data": ... }` envelope. */
    def data(body: js.Any): js.Dynamic = body.asInstanceOf[js.Dynamic].data

    /** The error body for approved messages in the default `{ "errors": [...] }` envelope. */
    def errors(messages: String*): String =
      wire(js.Dynamic.literal(errors = js.Array(messages.map(message => js.Dynamic.literal(message = message))*)))

    /** The `debug.cause` text of the first error entry. */
    def cause(body: js.Any): String =
      body.asInstanceOf[js.Dynamic].errors.asInstanceOf[js.Array[js.Dynamic]](0).debug.cause.asInstanceOf[String]

    /** A few event-loop turns, for checks that something did NOT happen. */
    def turns(count: Int): UIO[Unit] =
      ZIO.async[Any, Nothing, Unit](done => js.timers.setTimeout(0)(done(ZIO.unit))).repeatN(count - 1)

    /** Runs a handler function without h3: a fake request, a fresh head and a request scope. */
    def direct[E, A](program: ZIO[HandlerEnv, E, A]): IO[E, (ResponseHead, A)] =
      ZIO.scoped(ZIO.stateful[Scope](ResponseHead.empty)(program.zip(Response.head).map((value, head) => (head, value))))
end ZioTesting
