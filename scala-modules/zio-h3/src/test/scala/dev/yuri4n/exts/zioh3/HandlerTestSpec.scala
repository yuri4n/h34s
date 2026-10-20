package dev.yuri4n.exts.zioh3

import org.getshaka.nativeconverter.NativeConverter
import zio.*
import zio.stream.ZStream
import zio.test.*

// claude 2026-10-20: created: the public test helper ZHandler.test (ADR-0006, Q10)
/** `ZHandler.test`: a handler function runs without h3, and the test gets the head and the body. */
object HandlerTestSpec extends ZIOSpecDefault:
    final case class Input(name: String) derives NativeConverter
    final case class Greeting(message: String) derives NativeConverter
    final case class GreetingData(data: Greeting) derives NativeConverter

    trait Greetings:
        def greet(name: String): UIO[String]

    def spec = suite("ZHandler.test")(
      // The guide's example (docs/content/1.guide/10.testing.md, "ZIO: ZHandler.test"): keep them equal.
      test("the guide's example: the head and the returned value of a handler function") {
        // region guide-zio-test
        val handler: Request => ZIO[HandlerEnv, Throwable, Greeting] = request =>
          for
            input <- request.body.json[Input]
            _     <- Response.status(Status.Created)
          yield Greeting(s"Hello, ${input.name}")

        val request = Request.fake(method = "POST", headers = Map("content-type" -> "application/json"), body = Some("""{"name":"Scala"}"""))
        for response <- ZHandler.test(handler)(request)
        yield assertTrue(
          response.status.contains(Status.Created),
          response.body == TestResponse.Body.Value(Greeting("Hello, Scala")),
        )
        // endregion guide-zio-test
      },
      test("services are provided inside the function, as for ZHandler.define; a typed error stays typed") {
        // region guide-zio-services
        val handler: Request => ZIO[HandlerEnv & Greetings, HttpError, Greeting] = request =>
          for
            name     <- ZIO.fromOption(request.query.get("name")).orElseFail(HttpError(400, "name is required"))
            greeting <- ZIO.serviceWithZIO[Greetings](_.greet(name))
          yield Greeting(greeting)

        val fake = new Greetings:
            def greet(name: String): UIO[String] = ZIO.succeed(s"Hi, $name")
        def run(request: Request) =
          ZHandler.test(request => handler(request).provideSomeEnvironment[HandlerEnv](_.add[Greetings](fake)))(request)

        for
          greeted  <- run(Request.fake(query = Map("name" -> List("Ada"))))
          rejected <- run(Request.fake()).either
        yield assertTrue(
          greeted.body == TestResponse.Body.Value(Greeting("Hi, Ada")),
          rejected.left.toOption.map(_.status).contains(400),
        )
        // endregion guide-zio-services
      },
      test("the guide's stream example: a sent stream is collected in order") {
        // region guide-zio-stream
        val handler: Request => ZIO[HandlerEnv, Throwable, Sent] = _ =>
          Response.header("content-type", "application/x-ndjson") *> Response.sendStream(ZStream("1\n", "2\n"))

        for response <- ZHandler.test(handler)(Request.fake())
        yield assertTrue(response.body == TestResponse.Body.Streamed(List("1\n", "2\n")))
        // endregion guide-zio-stream
      },
      test("a sent stream's scope closes before the request scope") {
        for
          events <- Ref.make(Vector.empty[String])
          stream = ZStream.unwrapScoped(
            ZIO.acquireRelease(ZIO.unit)(_ => events.update(_ :+ "stream released")).as(ZStream("1\n", "2\n")),
          )
          handler = (_: Request) => ZIO.addFinalizer(events.update(_ :+ "request released")) *> Response.sendStream(stream)
          response <- ZHandler.test(handler)(Request.fake())
          order <- events.get
        yield assertTrue(
          response.body == TestResponse.Body.Streamed(List("1\n", "2\n")),
          order == Vector("stream released", "request released"),
        )
      },
      // claude 2026-10-20: created: after sendStream, the result's head is the one at the call, as on the wire
      test("after sendStream, the head is the one at the call: a later change is not in the result, as it is not on the wire") {
        val handler = (_: Request) =>
          Response.status(Status.Accepted) *> Response.sendStream(ZStream("a")).flatMap(sent =>
            Response.status(Status.Created) *> Response.header("x-late", "1").as(sent),
          )
        for response <- ZHandler.test(handler)(Request.fake())
        yield assertTrue(
          response.status.contains(Status.Accepted),
          response.header("x-late").isEmpty,
          response.body == TestResponse.Body.Streamed(List("a")),
        )
      },
      test("a stream that fails after an element fails the effect with the stream's own error") {
        val failure = new Exception("stream failed")
        for result <- ZHandler.test(_ => Response.sendStream(ZStream("one") ++ ZStream.fail(failure)))(Request.fake()).either
        yield assertTrue(result == Left(failure))
      },
      // claude 2026-10-20: created: the testing guide's Fetch.fake example for ZIO (docs/content/1.guide/10.testing.md, "Answer $fetch calls"); keep them equal (ADR-0005 gap 6)
      test("Fetch.fake answers $fetch from its map and records the requests; an unknown URL answers 404") {
        // region guide-zio-fetch
        val fetch = Fetch.fake(Map(
          "/api/greeting" -> FetchResponse.Plain(200, Map("content-type" -> "application/json"), """{"data":{"message":"hi"}}"""),
        ))
        val handler: Request => ZIO[HandlerEnv, FetchError, String] = _ =>
          for
            greeting <- ZHandler.$fetch.get[GreetingData]("/api/greeting")
            missing  <- ZHandler.$fetch.raw("/api/missing")
          yield s"${greeting.data.message} ${missing.status}"

        for response <- ZHandler.test(handler)(Request.fake(), fetch)
        yield assertTrue(
          response.body == TestResponse.Body.Value("hi 404"),
          fetch.requests.map(_.url) == List("/api/greeting", "/api/missing"),
        )
        // endregion guide-zio-fetch
      },
      // claude 2026-10-20: created: a fake streamed response is a ZStream; a fake body failure is a typed FetchError (ADR-0005 gap 6)
      test("a fake Streamed response is read as a ZStream, and its typed FetchError reaches the handler") {
        val broken = FetchError.BodyFailed(200, "the body ended with an error before its end")
        val fetch  = Fetch.fake(Map(
          "/api/events" -> FetchResponse.Streamed(200, Map("x-h34s-stream" -> "1"), ZStream("a", "b")),
          "/api/broken" -> FetchResponse.Streamed(200, Map.empty, ZStream("c") ++ ZStream.fail(broken)),
        ))
        val handler = (_: Request) =>
          for
            events <- ZHandler.$fetch.text("/api/events")
            failed <- ZHandler.$fetch.text("/api/broken").either
          yield s"$events ${failed.left.toOption.contains(broken)}"
        for response <- ZHandler.test(handler)(Request.fake(), fetch)
        yield assertTrue(response.body == TestResponse.Body.Value("ab true"))
      },
      // claude 2026-10-20: created: without a Fetch, $fetch dies and names ZHandler.test (ADR-0006, Q10)
      test("without a Fetch, $fetch dies, and the message names ZHandler.test and Fetch.fake") {
        val handler = (_: Request) => ZHandler.$fetch.text("/api/greeting")
        for result <- ZHandler.test(handler)(Request.fake()).exit
        yield assertTrue(
          result.causeOption.flatMap(_.dieOption).exists(_.getMessage.contains("pass a Fetch to ZHandler.test, such as Fetch.fake")),
        )
      },
      test("headers keep sets and appends in order; Unit is a value") {
        val handler = (_: Request) =>
          Response.header("x-a", "1") *> Response.appendHeader("x-a", "2") *> Response.header("x-b", "1") *> Response.header("x-b", "2")
        for response <- ZHandler.test(handler)(Request.fake())
        yield assertTrue(
          response.body == TestResponse.Body.Value(()),
          response.headerValues("X-A") == List("1", "2"),
          response.header("x-b").contains("2"),
          response.header("x-c").isEmpty,
        )
      },
      // claude 2026-10-20: created: ZHandler.test collects the frames of sendEvents, with the event-stream head (ADR-0007)
      // The testing guide's sendEvents example (docs/content/1.guide/10.testing.md): keep them equal.
      test("the guide's sendEvents example: the body holds the frames, and the head has the event-stream headers") {
        val handler: Request => ZIO[HandlerEnv, Throwable, Sent] = _ =>
          Response.sendEvents(ZStream(ServerEvent.json(Greeting("hi"), event = Some("greeting")), ServerEvent("bye")))

        for response <- ZHandler.test(handler)(Request.fake())
        yield assertTrue(
          response.body == TestResponse.Body.Streamed(List("event: greeting\ndata: {\"message\":\"hi\"}\n\n", "data: bye\n\n")),
          response.header("content-type").contains("text/event-stream"),
          response.header("cache-control").contains("private, no-cache, no-store, no-transform, must-revalidate, max-age=0"),
          response.header("x-accel-buffering").contains("no"),
          response.header("connection").contains("keep-alive"),
        )
      },
      // claude 2026-10-20: created: $fetch.events reads a fake event stream by its content type, Plain or Streamed, and asks for text/event-stream (ADR-0007)
      test("$fetch.events decodes a fake event stream, Plain or Streamed, by its content type and sends accept: text/event-stream") {
        val eventStream = Map("content-type" -> "text/event-stream")
        val fetch = Fetch.fake(Map(
          "/api/plain"    -> FetchResponse.Plain(200, eventStream, "data: {\"message\":\"one\"}\n\n"),
          "/api/streamed" -> FetchResponse.Streamed(200, eventStream, ZStream("data: {\"message\":", "\"two\"}\n", "\n")),
        ))
        val handler = (_: Request) =>
          for
            plain <- ZHandler.$fetch.events[Greeting]("/api/plain").flatMap(_.runCollect)
            streamed <- ZHandler.$fetch.events[Greeting]("/api/streamed", headers = Seq("Accept" -> "text/event-stream;q=1")).flatMap(_.runCollect)
          yield (plain ++ streamed).map(_.message).mkString(",")
        for response <- ZHandler.test(handler)(Request.fake(), fetch)
        yield assertTrue(
          response.body == TestResponse.Body.Value("one,two"),
          fetch.requests.map(_.headers) == List(Seq("accept" -> "text/event-stream"), Seq("Accept" -> "text/event-stream;q=1")),
        )
      },
    ) @@ TestAspect.timeout(30.seconds)
end HandlerTestSpec
