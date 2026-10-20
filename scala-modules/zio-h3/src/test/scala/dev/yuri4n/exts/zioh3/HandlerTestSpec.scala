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
      test("a sent stream is collected in order; the stream's scope closes before the request scope") {
        for
          events <- Ref.make(Vector.empty[String])
          stream = ZStream.unwrapScoped(
            ZIO.acquireRelease(ZIO.unit)(_ => events.update(_ :+ "stream released")).as(ZStream("1\n", "2\n")),
          )
          // region guide-zio-stream
          handler = (_: Request) =>
            ZIO.addFinalizer(events.update(_ :+ "request released")) *>
              Response.header("content-type", "application/x-ndjson") *>
              Response.sendStream(stream)
          response <- ZHandler.test(handler)(Request.fake())
          // endregion guide-zio-stream
          order <- events.get
        yield assertTrue(
          response.body == TestResponse.Body.Streamed(List("1\n", "2\n")),
          response.header("content-type").contains("application/x-ndjson"),
          order == Vector("stream released", "request released"),
        )
      },
      test("a stream that fails after an element fails the effect with the stream's own error") {
        val failure = new Exception("stream failed")
        for result <- ZHandler.test(_ => Response.sendStream(ZStream("one") ++ ZStream.fail(failure)))(Request.fake()).either
        yield assertTrue(result == Left(failure))
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
    ) @@ TestAspect.timeout(30.seconds)
end HandlerTestSpec
