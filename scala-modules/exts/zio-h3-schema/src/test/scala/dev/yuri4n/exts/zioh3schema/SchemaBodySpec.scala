package dev.yuri4n.exts.zioh3schema

import dev.yuri4n.exts.h3.CommonEvHandler
import dev.yuri4n.exts.zioh3.ZHandler
import org.getshaka.nativeconverter.NativeConverter
import typings.h3.mod.{EventHandlerRequest, H3Event}
import zio.*
import zio.schema.{DeriveSchema, Schema, StandardType}
import zio.schema.validation.Validation
import zio.test.*

import scala.scalajs.js

object SchemaBodySpec extends ZIOSpecDefault:
    final case class Input(text: String)
    final case class NativeOnly(text: String) derives NativeConverter
    given Schema[Input] =
      given Schema[String] = Schema.Primitive(StandardType.StringType).transform(_.trim, identity)
        .validation(Validation.minLength(1) && Validation.maxLength(4))
      DeriveSchema.gen[Input]

    private def invoke(handler: CommonEvHandler, body: String, contentType: String = "application/json"): Task[(Int, js.Any)] =
      val response = js.Dynamic.literal(statusCode = 200, statusMessage = "OK", headersSent = false, writableEnded = false)
      val request = js.Dynamic.literal(method = "POST", url = "/schema", headers = js.Dynamic.literal("content-type" -> contentType), body = body)
      val event = new H3Event[EventHandlerRequest](request, response)
      ZIO.fromPromiseJS(handler(event).asInstanceOf[js.Promise[js.Any]])
        .map(result => (response.statusCode.asInstanceOf[Int], result))

    private val handler = ZHandler.define(ZHandler.readBodyWithSchema[Input].map(_.text))

    def spec = suite("optional schema request boundary")(
      test("reads a normalized model with no endpoint validation plumbing") {
        for result <- invoke(handler, "{\"text\":\" ok \"}")
        yield assertTrue(result._1 == 200, result._2.asInstanceOf[String] == "ok")
      },
      test("returns safe 422 for malformed JSON, structure, primitive types, and domain failures") {
        val invalid = List("{", "null", "[]", "true", "{}", "{\"text\":null}", "{\"text\":42}", "{\"text\":{}}",
          "{\"text\":\" \"}", "{\"text\":\"abcde\"}")
        for results <- ZIO.foreach(invalid)(invoke(handler, _))
        yield assertTrue(results.forall { case (status, body) =>
          status == 422 && js.JSON.stringify(body) == "{\"errors\":[\"Request body does not match schema\"]}"
        })
      },
      test("keeps unexpected decoder exceptions opaque") {
        given Schema[String] = Schema.Primitive(StandardType.StringType)
          .transform[String](_ => throw new IllegalStateException("private schema detail"), identity)
        for result <- invoke(ZHandler.define(ZHandler.readBodyWithSchema[String]), "\"value\"")
        yield assertTrue(result._1 == 500, js.JSON.stringify(result._2) == "{\"errors\":[\"Internal server error\"]}")
      },
      test("uses strict parsing for JSON charset headers while preserving plain reader defaults") {
        val contentType = "application/json; charset=utf-8"
        for
          strict <- invoke(ZHandler.define(ZHandler.readBodyWithSchema[String]), "{", contentType)
          plain <- invoke(ZHandler.define(ZHandler.readBody[String]), "{", contentType)
        yield assertTrue(strict._1 == 422, plain._1 == 200, plain._2.asInstanceOf[String] == "{")
      },
      test("keeps unsupported schema configuration opaque rather than blaming input") {
        for result <- invoke(ZHandler.define(ZHandler.readBodyWithSchema[Map[String, String]].as("ok")), "{}")
        yield assertTrue(result._1 == 500, js.JSON.stringify(result._2) == "{\"errors\":[\"Internal server error\"]}")
      },
      test("preserves NativeConverter-only readBody with the schema extension imported") {
        for result <- invoke(ZHandler.define(ZHandler.readBody[NativeOnly].map(_.text)), "{\"text\":\"native\"}")
        yield assertTrue(result._1 == 200, result._2.asInstanceOf[String] == "native")
      },
      test("allows an explicit application policy for expected validation errors") {
        val program = ZHandler.readBodyWithSchema[Input].catchSome {
          case _: InvalidBody => ZHandler.failWith(400, "Please provide valid input")
        }.map(_.text)
        for result <- invoke(ZHandler.define(program), "{}")
        yield assertTrue(result._1 == 400, js.JSON.stringify(result._2) == "{\"errors\":[\"Please provide valid input\"]}")
      },
    )
