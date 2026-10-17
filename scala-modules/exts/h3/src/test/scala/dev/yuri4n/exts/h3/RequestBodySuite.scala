package dev.yuri4n.exts.h3

import dev.yuri4n.exts.h3.H3Fixtures.{FakeRequest, FakeResponse}
import dev.yuri4n.exts.h3.internal.{BodyDecoding, BodyReadFailure}
import munit.FunSuite
import org.getshaka.nativeconverter.{NativeConverter, ParseState}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js

class RequestBodySuite extends FunSuite:
    given ExecutionContext = ExecutionContext.global

    private type Outcome = Either[BodyReadFailure, Option[String]]

    private def read(body: RequestBody, limit: Long): Future[Outcome] =
      val result = Promise[Outcome]()
      body.readText(BodyLimit(limit))(outcome => result.success(outcome))
      result.future

    private def post(headers: Seq[(String, String)]): (FakeRequest, Request) =
      val request = FakeRequest("POST", "/body", headers)
      (request, Request.fromEvent(H3Fixtures.event(request, FakeResponse())))

    test("a declared Content-Length above the limit fails before reading") {
      val (native, request) = post(Seq("content-length" -> "11"))
      read(request.body, 10).map { outcome =>
        assertEquals(outcome, Left(BodyReadFailure.TooLarge))
        assertEquals(native.listeners("data"), 0)
      }
    }

    test("a chunked body is counted while streaming and readers clean up their listeners") {
      val (native, request) = post(Seq("transfer-encoding" -> "chunked"))
      val outcome = read(request.body, 8)
      native.send("12345")
      native.send("67890")
      outcome.map { result =>
        assertEquals(result, Left(BodyReadFailure.TooLarge))
        assertEquals(native.listeners("data"), 0)
        assertEquals(native.listeners("end"), 0)
      }
    }

    test("the body is read once; later reads return the same text") {
      val (native, request) = post(Seq("content-length" -> "13"))
      val first = read(request.body, 100)
      val concurrent = read(request.body, 100)
      native.send("{\"a\":")
      native.send("\"héllo\"}")
      native.finish()
      for
        a <- first
        b <- concurrent
        c <- read(request.body, 100)
        tooSmall <- read(request.body, 5)
      yield
        assertEquals(a, Right(Some("{\"a\":\"héllo\"}")))
        assertEquals(b, a)
        assertEquals(c, a)
        assertEquals(tooSmall, Left(BodyReadFailure.TooLarge))
        assertEquals(native.listeners("data"), 0)
    }

    test("a request without Content-Length or chunked encoding has no body") {
      val (_, request) = post(Nil)
      read(request.body, 100).map(outcome => assertEquals(outcome, Right(None)))
    }

    test("bodies buffered by upstream middleware are honoured and still limited") {
      val objectBody = FakeRequest("POST", "/body", Seq("content-length" -> "100"))
      objectBody.native.body = js.Dynamic.literal(name = "Scala")
      val rawBody = FakeRequest("POST", "/body", Nil)
      rawBody.native.rawBody = "raw text"
      val large = FakeRequest("POST", "/body", Nil)
      large.native.rawBody = "0123456789"
      def body(request: FakeRequest) = Request.fromEvent(H3Fixtures.event(request, FakeResponse())).body
      for
        parsed <- read(body(objectBody), 100)
        raw <- read(body(rawBody), 100)
        limited <- read(body(large), 5)
      yield
        assertEquals(parsed, Right(Some("{\"name\":\"Scala\"}")))
        assertEquals(raw, Right(Some("raw text")))
        assertEquals(limited, Left(BodyReadFailure.TooLarge))
    }

    test("a fake body is limited by bytes, not characters") {
      val body = Request.fake(body = Some("ééé")).body
      for
        fits <- read(body, 6)
        tooLarge <- read(body, 5)
      yield
        assertEquals(fits, Right(Some("ééé")))
        assertEquals(tooLarge, Left(BodyReadFailure.TooLarge))
    }

    test("JSON is accepted only for absent or JSON media types") {
      assert(BodyDecoding.acceptsJson(None))
      assert(BodyDecoding.acceptsJson(Some("application/json")))
      assert(BodyDecoding.acceptsJson(Some("Application/JSON; charset=utf-8")))
      assert(BodyDecoding.acceptsJson(Some("application/problem+json")))
      assert(!BodyDecoding.acceptsJson(Some("application/x-www-form-urlencoded")))
      assert(!BodyDecoding.acceptsJson(Some("text/plain")))
      assert(!BodyDecoding.acceptsJson(Some("text/json")))
    }

    test("JSON parsing is strict and drops __proto__ keys") {
      assertEquals(BodyDecoding.parseJson(Some("{invalid")), Left(BodyDecoding.invalidJson))
      assert(js.isUndefined(BodyDecoding.parseJson(None).toOption.get))
      val parsed = BodyDecoding.parseJson(Some("{\"__proto__\":{\"admin\":true},\"a\":1}")).toOption.get
      assert(!js.Object.hasProperty(parsed.asInstanceOf[js.Object], "admin"))
      assert(!parsed.asInstanceOf[js.Dictionary[js.Any]].contains("__proto__"))
      assertEquals(parsed.asInstanceOf[js.Dynamic].a.asInstanceOf[Int], 1)
    }

    test("shape failures become InvalidInput; other converter exceptions stay defects") {
      final case class Input(name: String, count: Int) derives NativeConverter
      final case class Schema(name: String)
      given NativeConverter[Schema]:
        extension (value: Schema) def toNative: js.Any = value.name
        def fromNative(state: ParseState): Schema =
          if state.json == null then throw InvalidInput(List("name: must be present")) else Schema("ok")
      final case class Broken(value: String)
      given NativeConverter[Broken]:
        extension (value: Broken) def toNative: js.Any = value.value
        def fromNative(state: ParseState): Broken = throw IllegalStateException("private decoder detail")

      assertEquals(BodyDecoding.decode[Input](js.JSON.parse("{\"name\":\"a\",\"count\":\"x\"}")), Left(InvalidInput()))
      assertEquals(BodyDecoding.decode[Input](js.JSON.parse("\"text\"")), Left(InvalidInput()))
      assertEquals(BodyDecoding.decode[Schema](null), Left(InvalidInput(List("name: must be present"))))
      assertEquals(BodyDecoding.decode[Input](js.JSON.parse("{\"name\":\"a\",\"count\":2}")), Right(Input("a", 2)))
      intercept[IllegalStateException](BodyDecoding.decode[Broken](js.JSON.parse("{}")))
    }

    test("shape error paths never include the value") {
      final case class Database(url: String) derives NativeConverter
      final case class Config(database: Database) derives NativeConverter
      val error = intercept[RuntimeException] {
        summon[NativeConverter[Config]].fromNative(js.JSON.parse("{\"database\":{\"url\":42}}"))
      }
      assertEquals(BodyDecoding.shapeErrorPath(error), Some("root.database.url"))
      assertEquals(BodyDecoding.shapeErrorPath(IllegalStateException("other")), None)
    }
end RequestBodySuite
