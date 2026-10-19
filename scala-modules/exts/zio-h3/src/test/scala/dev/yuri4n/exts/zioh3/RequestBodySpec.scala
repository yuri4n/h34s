package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.ntcvzioschema.NativeSchema
import dev.yuri4n.exts.zioh3.ZioTesting.*
import org.getshaka.nativeconverter.{NativeConverter, ParseState}
import zio.*
import zio.schema.annotation.validate
import zio.schema.validation.Validation
import zio.schema.{DeriveSchema, Schema, StandardType, derived}
import zio.test.*

import scala.scalajs.js

/** Typed body reads (C8, K15-K17, D3) and the ZIO Schema request contracts (S1-S7, ported from the
  * removed zio-h3-schema module to `derives NativeSchema` and `request.body.json`).
  */
object RequestBodySpec extends ZIOSpecDefault:
    final case class Input(name: String) derives NativeConverter

    /** One derivation: ZIO Schema with field checks, and the native converter derived from it. */
    final case class Signup(
        @validate(Validation.minLength(3)) name: String,
        @validate(Validation.greaterThan(17)) age: Int,
    ) derives Schema, NativeSchema

    /** A model whose field schema normalizes (trim) and validates (1-4 characters). */
    final case class Note(text: String)
    object Note:
        given Schema[Note] =
          given Schema[String] = Schema.Primitive(StandardType.StringType).transform(_.trim, identity)
            .validation(Validation.minLength(1) && Validation.maxLength(4))
          DeriveSchema.gen[Note]
        given NativeConverter[Note] = NativeSchema.derived

    private val note = ZHandler.define(_.body.json[Note].map(_.text))

    def spec = suite("request bodies")(
      test("an absent JSON body is a 422 for a model and None for an Option") {
        val model = ZHandler.define(_.body.json[Input].map(_.name))
        val optional = ZHandler.define(_.body.json[Option[Input]].map(_.fold("none")(_.name)))
        def empty = Call(method = "POST", headers = Seq("content-type" -> "application/json", "content-length" -> "0")).body("")
        val modelCall = empty
        val optionalCall = empty
        for
          modelBody <- modelCall.invoke(model)
          optionalBody <- optionalCall.invoke(optional)
        yield assertTrue(
          modelCall.response.status == 422, wire(modelBody) == errors(InvalidInput.genericMessage),
          optionalCall.response.status == 200, wire(optionalBody) == "{\"data\":\"none\"}",
        )
      },
      test("requireJsonContentType rejects a JSON body without a content type with 415") {
        val strict = ZHandler.define(HandlerOptions(requireJsonContentType = true))(_.body.json[Input].map(_.name))
        val call = Call(method = "POST", headers = Seq("content-length" -> "16")).body("{\"name\":\"Scala\"}")
        for body <- call.invoke(strict)
        yield assertTrue(call.response.status == 415, wire(body) == errors("Request body must be JSON"))
      },
      // claude 2026-10-19: created: negative control for issue #41 (strict JSON content type by default)
      test("by default a JSON body without a content type is a 415; requireJsonContentType = false accepts it") {
        val default = ZHandler.define(_.body.json[Input].map(_.name))
        val lenient = ZHandler.define(HandlerOptions(requireJsonContentType = false))(_.body.json[Input].map(_.name))
        def untyped = Call(method = "POST", headers = Seq("content-length" -> "16")).body("{\"name\":\"Scala\"}")
        val rejected = untyped
        val accepted = untyped
        for
          rejectedBody <- rejected.invoke(default)
          acceptedBody <- accepted.invoke(lenient)
        yield assertTrue(
          rejected.response.status == 415, wire(rejectedBody) == errors("Request body must be JSON"),
          accepted.response.status == 200, wire(acceptedBody) == "{\"data\":\"Scala\"}",
        )
      },
      // K15
      test("decodes a JSON body with a NativeConverter") {
        for body <- post("{\"name\":\"Scala\"}").invoke(ZHandler.define(_.body.json[Input].map(input => s"Hello ${input.name}")))
        yield assertTrue(wire(body) == "{\"data\":\"Hello Scala\"}")
      },
      // K17
      test("invalid JSON is a typed 400 that the handler can recover") {
        val recovering = ZHandler.define(_.body.json[Input].map(_.name).catchSome {
          case error: HttpError => ZIO.succeed(s"recovered: ${error.message}")
        })
        val rendering = post("{invalid json}")
        for
          recovered <- post("{invalid json}").invoke(recovering)
          rendered <- rendering.invoke(ZHandler.define(_.body.json[Input].map(_.name)))
        yield assertTrue(
          wire(recovered) == "{\"data\":\"recovered: Request body is not valid JSON\"}",
          rendering.response.status == 400, wire(rendered) == errors("Request body is not valid JSON"),
        )
      },
      test("shape failures are a generic 422; other decoder exceptions are opaque 500s") {
        final case class Counted(count: Int) derives NativeConverter
        final case class Strict(value: String)
        given NativeConverter[Strict]:
          extension (value: Strict) def toNative: js.Any = value.value
          def fromNative(state: ParseState): Strict = throw RuntimeException("private decoder detail")
        val shape = post("{\"count\":\"many\"}")
        val defect = post("{\"value\":\"x\"}")
        for
          shapeBody <- shape.invoke(ZHandler.define(_.body.json[Counted].map(_.count)))
          defectBody <- defect.invoke(ZHandler.define(_.body.json[Strict].map(_.value)))
        yield assertTrue(
          shape.response.status == 422, wire(shapeBody) == errors("Request body does not match the expected shape"),
          defect.response.status == 500, wire(defectBody) == opaque,
        )
      },
      // K16, D3
      test("a SafeError thrown by a decoder is a defect: opaque, with diagnostics only in development") {
        final case class BrokenInput(value: String)
        final case class DecoderDefect(message: String) extends SafeError:
          override def status: Int = 422
        given NativeConverter[BrokenInput]:
          extension (value: BrokenInput) def toNative: js.Any = value.value
          def fromNative(state: ParseState): BrokenInput = throw DecoderDefect("private decoder detail")
        ZIO.foreach(List(false, true)) { development =>
          var releases = 0
          val call = post("{\"value\":\"ignored\"}", development = development)
          val handler = ZHandler.define { request =>
            ZIO.acquireRelease(ZIO.unit)(_ => ZIO.succeed { releases += 1 }) *> request.body.json[BrokenInput].map(_.value)
          }
          call.invoke(handler).map { body =>
            call.response.status == 500 && releases == 1 &&
            (if development then cause(body).contains("private decoder detail") else wire(body) == opaque)
          }
        }.map(results => assertTrue(results == List(true, true)))
      },
      test("typed reads reject other media types with 415 and large bodies with 413") {
        val form = Call(method = "POST", headers = Seq("content-type" -> "application/x-www-form-urlencoded", "content-length" -> "10"))
          .body("name=Scala")
        val large = post("{\"name\":\"" + ("x" * 32) + "\"}")
        for
          formBody <- form.invoke(ZHandler.define(_.body.json[Input].map(_.name)))
          largeBody <- large.invoke(ZHandler.define(_.body.json[Input](BodyLimit(16)).map(_.name)))
        yield assertTrue(
          form.response.status == 415, wire(formBody) == errors("Request body must be JSON"),
          large.response.status == 413, wire(largeBody) == errors("Request body is too large"),
        )
      },
      test("the body is one-shot but reading it twice returns the same text") {
        val handler = ZHandler.define { request =>
          for
            input <- request.body.json[Input]
            text <- request.body.text()
          yield s"${input.name}|$text"
        }
        for body <- post("{\"name\":\"A\"}").invoke(handler)
        yield assertTrue(data(body).asInstanceOf[String] == "A|{\"name\":\"A\"}")
      },
      test("param returns a router parameter or a 400") {
        for
          present <- Request.fake(routerParams = Map("id" -> "7")).param("id")
          missing <- Request.fake().param("id").either
        yield assertTrue(present == "7", missing == Left(HttpError.badRequest("Missing router parameter: id")))
      },
      // S1
      test("a schema-backed model is normalized by its schema, with no endpoint validation code") {
        for body <- post("{\"text\":\" ok \"}").invoke(note)
        yield assertTrue(wire(body) == "{\"data\":\"ok\"}")
      },
      // S2
      test("malformed JSON is a 400; wrong shapes are a generic 422; domain failures list their checks") {
        val structural = List("null", "[]", "true", "{}", "{\"text\":null}", "{\"text\":42}", "{\"text\":{}}")
        for
          malformed <- post("{").pipe(call => call.invoke(note).map(call.response.status -> wire(_)))
          shapes <- ZIO.foreach(structural)(text => post(text).pipe(call => call.invoke(note).map(call.response.status -> wire(_))))
          blank <- post("{\"text\":\" \"}").pipe(call => call.invoke(note).map(call.response.status -> wire(_)))
          long <- post("{\"text\":\"abcde\"}").pipe(call => call.invoke(note).map(call.response.status -> wire(_)))
        yield assertTrue(
          malformed == (400 -> errors("Request body is not valid JSON")),
          shapes.forall(_ == (422 -> errors("Request body does not match the expected shape"))),
          blank == (422 -> "{\"errors\":[{\"message\":\"must have at least 1 character\",\"path\":\"text\"}]}"),
          long == (422 -> "{\"errors\":[{\"message\":\"must have at most 4 characters\",\"path\":\"text\"}]}"),
        )
      },
      test("`derives Schema, NativeSchema` reports every failed check with its path and never the input") {
        val short = post("{\"name\":\"Al\",\"age\":12}")
        for
          body <- short.invoke(ZHandler.define(_.body.json[Signup].map(_.name)))
          ok <- post("{\"name\":\"Ada\",\"age\":36}").invoke(ZHandler.define(_.body.json[Signup].map(signup => s"${signup.name} ${signup.age}")))
        yield assertTrue(
          short.response.status == 422,
          wire(body) == "{\"errors\":[{\"message\":\"must have at least 3 characters\",\"path\":\"name\"}," +
            "{\"message\":\"must be greater than 17\",\"path\":\"age\"}]}",
          !wire(body).contains("Al\""), !wire(body).contains("12"),
          data(ok).asInstanceOf[String] == "Ada 36",
        )
      },
      // S3
      test("a schema transform that throws is a defect, not invalid input") {
        val throwing: Schema[String] = Schema.Primitive(StandardType.StringType)
          .transform[String](_ => throw new IllegalStateException("private schema detail"), identity)
        val call = post("\"value\"")
        for body <- call.invoke(ZHandler.define(_.body.json[String](using NativeSchema.derived(using throwing))))
        yield assertTrue(call.response.status == 500, wire(body) == opaque)
      },
      // S4
      test("schema reads parse strictly for JSON with a charset; text reads return the raw body") {
        val strict = post("{", "application/json; charset=utf-8")
        for
          strictBody <- strict.invoke(note)
          plain <- post("{", "application/json; charset=utf-8").invoke(ZHandler.define(_.body.text()))
        yield assertTrue(strict.response.status == 400, wire(strictBody) == errors("Request body is not valid JSON"), data(plain).asInstanceOf[String] == "{")
      },
      // S5
      test("an unsupported schema is an opaque 500, not blamed on the input") {
        val call = post("{}")
        for body <- call.invoke(ZHandler.define(_.body.json[Map[String, String]](using NativeSchema.derived).as("ok")))
        yield assertTrue(call.response.status == 500, wire(body) == opaque)
      },
      // S6
      test("NativeConverter-only reads keep working next to schema-derived models") {
        for body <- post("{\"name\":\"native\"}").invoke(ZHandler.define(_.body.json[Input].map(_.name)))
        yield assertTrue(data(body).asInstanceOf[String] == "native")
      },
      // S7
      test("the application can map invalid input to its own policy") {
        val call = post("{}")
        val handler = ZHandler.define(_.body.json[Note].map(_.text).catchSome {
          case _: InvalidInput => ZHandler.failWith(400, "Please provide valid input")
        })
        for body <- call.invoke(handler)
        yield assertTrue(call.response.status == 400, wire(body) == errors("Please provide valid input"))
      },
    ) @@ TestAspect.timeout(45.seconds)

    extension (call: Call) private def pipe[A](f: Call => A): A = f(call)
end RequestBodySpec
