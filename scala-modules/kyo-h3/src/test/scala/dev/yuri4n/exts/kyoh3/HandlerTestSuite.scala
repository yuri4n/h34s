package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.kyoh3.KyoTesting.*
import kyo.*
import munit.FunSuite
import org.getshaka.nativeconverter.NativeConverter

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

// claude 2026-10-20: created: the public test helper KHandler.test and Fetch.fake (ADR-0006, Q10)
/** `KHandler.test`: a handler function runs without h3, and the test gets the head and the body. */
class HandlerTestSuite extends FunSuite:
    given ExecutionContext = ExecutionContext.global
    override val munitTimeout: FiniteDuration = 30.seconds

    final case class Input(name: String) derives NativeConverter
    final case class Greeting(message: String) derives NativeConverter
    final case class GreetingData(data: Greeting) derives NativeConverter

    /** Runs a test program to a Future and fails the test on a failure of the program. */
    private def response[B](program: TestResponse[B] < (Async & Abort[Throwable])): Future[TestResponse[B]] =
      run(program).map(_.getOrThrow)

    // The guide's example (docs/content/1.guide/10.testing.md, "Kyo: KHandler.test"): keep them equal.
    test("the guide's example: the head and the returned value of a handler function") {
      // region guide-kyo-test
      val handler: Request => Greeting < HandlerEffects = request =>
        for
          input <- request.body.json[Input]
          _     <- Response.status(Status.Created)
        yield Greeting(s"Hello, ${input.name}")

      val request = Request.fake(method = "POST", headers = Map("content-type" -> "application/json"), body = Some("""{"name":"Scala"}"""))
      val program = KHandler.test(handler)(request)
      // program: TestResponse[Greeting] < (Async & Abort[Throwable])

      import AllowUnsafe.embrace.danger
      Fiber.Unsafe.init(Abort.run[Throwable](program)).toFuture().map { result =>
        val response = result.getOrThrow
        assertEquals(response.status, Some(Status.Created))
        assertEquals(response.body, TestResponse.Body.Value(Greeting("Hello, Scala")))
      }
      // endregion guide-kyo-test
    }

    test("services are provided inside the function with Env.run, as for KHandler.define") {
      trait Greetings:
          def greet(name: String): String
      val fake = new Greetings:
          def greet(name: String): String = s"Hi, $name"
      // region guide-kyo-services
      val handler: Request => Greeting < (HandlerEffects & Env[Greetings]) = request =>
        Env.use[Greetings](greetings => Greeting(greetings.greet(request.query.getOrElse("name", "you"))))

      val program = KHandler.test(request => Env.run(fake)(handler(request)))(Request.fake(query = Map("name" -> List("Ada"))))
      // endregion guide-kyo-services
      response(program).map(result => assertEquals(result.body, TestResponse.Body.Value(Greeting("Hi, Ada"))))
    }

    test("the guide's stream example: a sent stream is collected in order") {
      // region guide-kyo-stream
      val handler: Request => Sent < HandlerEffects = _ =>
        Response.header("content-type", "application/x-ndjson").andThen(Response.sendStream(Stream.init(Seq("1\n", "2\n"))))

      val program = KHandler.test(handler)(Request.fake())
      // the body of the result: TestResponse.Body.Streamed(List("1\n", "2\n"))
      // endregion guide-kyo-stream
      response(program).map { result =>
        assertEquals(result.body, TestResponse.Body.Streamed(List("1\n", "2\n")))
        assertEquals(result.header("content-type"), Some("application/x-ndjson"))
      }
    }

    test("a sent stream's scope closes when the stream ends, before the request scope") {
      var events = Vector.empty[String]
      val stream = Stream[String, Async & Scope] {
        Scope.ensure(Sync.defer { events = events :+ "stream released" }).andThen(Emit.value(Chunk("1\n", "2\n")))
      }
      val handler: Request => Sent < HandlerEffects = _ =>
        Scope.ensure(Sync.defer { events = events :+ "request released" }).andThen(Response.sendStream(stream))
      response(KHandler.test(handler)(Request.fake())).map { result =>
        assertEquals(result.body, TestResponse.Body.Streamed(List("1\n", "2\n")))
        assertEquals(events, Vector("stream released", "request released"))
      }
    }

    test("Fetch.fake answers $fetch from its map and records the requests; an unknown URL answers 404") {
      // region guide-kyo-fetch
      val fetch = Fetch.fake(Map(
        "/api/greeting" -> FetchResponse.Plain(200, Map("content-type" -> "application/json"), """{"data":{"message":"hi"}}"""),
      ))
      val handler: Request => String < HandlerEffects = _ =>
        for
          greeting <- KHandler.$fetch.get[GreetingData]("/api/greeting")
          missing  <- KHandler.$fetch.raw("/api/missing")
        yield s"${greeting.data.message} ${missing.status}"

      val program = KHandler.test(handler)(Request.fake(), fetch)
      // endregion guide-kyo-fetch
      response(program).map { result =>
        assertEquals(result.body, TestResponse.Body.Value("hi 404"))
        assertEquals(fetch.requests.map(_.url), List("/api/greeting", "/api/missing"))
      }
    }

    test("without a Fetch, $fetch fails; a failure of the program stays a failure of the result") {
      val handler: Request => String < HandlerEffects = _ => KHandler.$fetch.text("/api/greeting")
      run(KHandler.test(handler)(Request.fake())).map { result =>
        assert(result.isPanic || result.isFailure, result.toString)
        assert(result.toString.contains("pass a Fetch to KHandler.test"), result.toString)
      }
    }

    test("headers keep sets and appends in order; Unit is a value; a typed failure keeps its type") {
      val headers: Request => Unit < HandlerEffects = _ =>
        Response.header("x-a", "1").andThen(Response.appendHeader("x-a", "2")).andThen(Response.header("x-b", "1"))
          .andThen(Response.header("x-b", "2"))
      val rejected: Request => Unit < HandlerEffects = _ => KHandler.failWith(409, "Busy")
      for
        unit <- response(KHandler.test(headers)(Request.fake()))
        failed <- run(Abort.run[HttpError](KHandler.test(rejected)(Request.fake())))
      yield
        assertEquals(unit.body, TestResponse.Body.Value(()))
        assertEquals(unit.headerValues("X-A"), List("1", "2"))
        assertEquals(unit.header("x-a"), Some("1, 2"))
        assertEquals(unit.header("x-b"), Some("2"))
        assertEquals(unit.header("x-c"), None)
        assertEquals(failed.getOrThrow.failure.map(_.status), Maybe(409))
    }

    test("a stream that fails after an element fails the result with the stream's error") {
      val failure = new Exception("stream failed")
      val handler: Request => Sent < HandlerEffects = _ =>
        Response.sendStream(Stream.init(Seq("one")).concat(Stream[String, Abort[Exception]](Abort.fail(failure))))
      run(KHandler.test(handler)(Request.fake())).map { result =>
        assertEquals(result, Result.fail(failure))
      }
    }
end HandlerTestSuite
