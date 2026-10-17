package dev.yuri4n.exts.h3

import munit.FunSuite
import org.getshaka.nativeconverter.NativeConverter

import scala.scalajs.js

class ValuesSuite extends FunSuite:
    test("Status accepts 100 to 599 only") {
      assertEquals(Status(201).map(_.code), Right(201))
      assert(Status(99).isLeft)
      assert(Status(600).isLeft)
      intercept[IllegalArgumentException](Status.unsafe(1000))
      assertEquals(Status.Found.code, 302)
    }

    test("head operations are ordered entries; the last status wins") {
      val head = ResponseHead.empty
        .withStatus(Status.Created)
        .withHeader("x-a", "1")
        .appendHeader("x-a", "2", OnFailure.Keep)
        .withCookie(SetCookie("session", "abc"))
        .withoutCookie("old", onFailure = OnFailure.Keep)
        .withStatus(Status.Accepted)
      assertEquals(head.status, Some(Status.Accepted))
      assertEquals(
        head.headers,
        Vector(
          HeaderEntry("x-a", "1", HeaderMode.Set, OnFailure.Discard),
          HeaderEntry("x-a", "2", HeaderMode.Append, OnFailure.Keep),
        ),
      )
      assertEquals(head.cookies.map(_.cookie), Vector(SetCookie("session", "abc"), DeleteCookie("old")))
      assertEquals(head.redirect("/next").headers.last, HeaderEntry("location", "/next", HeaderMode.Set, OnFailure.Discard))
      assertEquals(head.redirect("/next").status, Some(Status.Found))
    }

    test("success applies defaults then the program; the program status wins") {
      val defaults = ResponseHead.noStore.withStatus(Status.Ok)
      val program  = ResponseHead.empty.withStatus(Status.Created).withHeader("x-id", "7")
      val head     = ResponseHead.forSuccess(defaults, program)
      assertEquals(head.status, Some(Status.Created))
      assertEquals(head.headers.map(_.name), Vector("cache-control", "x-id"))
      assertEquals(ResponseHead.forSuccess(defaults, ResponseHead.empty).status, Some(Status.Ok))
    }

    test("failure keeps defaults, Keep entries and error headers, with the error status") {
      val defaults = ResponseHead.noStore
      val program = ResponseHead.empty
        .withStatus(Status.Created)
        .withHeader("x-discarded", "1")
        .withHeader("x-request-id", "abc", OnFailure.Keep)
        .withCookie(SetCookie("session", "abc"))
        .withCookie(SetCookie("trace", "t"), OnFailure.Keep)
      val head = ResponseHead.forFailure(defaults, program, Status.Unauthorized, List("WWW-Authenticate" -> "Bearer"))
      assertEquals(head.status, Some(Status.Unauthorized))
      assertEquals(head.headers.map(_.name), Vector("cache-control", "x-request-id", "WWW-Authenticate"))
      assertEquals(head.cookies.map(_.cookie), Vector(SetCookie("trace", "t")))
      assertEquals(program.onFailure.status, None)
    }

    test("errors carry status, message and headers") {
      val error = HttpError.unauthorized("Sign in").header("WWW-Authenticate", "Bearer")
      assertEquals(error.status, 401)
      assertEquals(error.getMessage, "Sign in")
      assertEquals(error.headers, List("WWW-Authenticate" -> "Bearer"))
      assertEquals(ConfigError("missing").getMessage, "missing")
    }

    test("a SafeError has one entry with its message unless it overrides errors") {
      assertEquals(HttpError.notFound("No such note").errors, List(ErrorEntry("No such note")))
      final case class Several(message: String) extends SafeError:
        override def errors: List[ErrorEntry] = List(ErrorEntry("a", Some("x")), ErrorEntry("b"))
      assertEquals(Several("ignored").errors.map(_.message), List("a", "b"))
    }

    test("InvalidInput is a 422 with its entries, or one generic entry without entries") {
      assertEquals(InvalidInput().status, 422)
      assertEquals(InvalidInput().errors, List(ErrorEntry(InvalidInput.genericMessage)))
      assertEquals(InvalidInput().message, InvalidInput.genericMessage)
      val entries = List(ErrorEntry("too short", Some("name")), ErrorEntry("one field is required"))
      assertEquals(InvalidInput(entries).errors, entries)
      assertEquals(InvalidInput(entries).message, "name: too short; one field is required")
      assertEquals(InvalidInput(entries).getMessage, "name: too short; one field is required")
    }

    test("ErrorEntry is { message, path? } natively; path is omitted when absent") {
      val converter = summon[NativeConverter[ErrorEntry]]
      assertEquals(js.JSON.stringify(converter.toNative(ErrorEntry("too short", Some("name")))), "{\"message\":\"too short\",\"path\":\"name\"}")
      val withoutPath = converter.toNative(ErrorEntry("required")).asInstanceOf[js.Object]
      assertEquals(js.JSON.stringify(withoutPath), "{\"message\":\"required\"}")
      assert(!withoutPath.hasOwnProperty("path"))
      assertEquals(converter.fromNative(js.JSON.parse("{\"message\":\"m\",\"path\":\"p\"}")), ErrorEntry("m", Some("p")))
      assertEquals(converter.fromNative(js.JSON.parse("{\"message\":\"m\",\"path\":null}")), ErrorEntry("m"))
      assertEquals(converter.fromNative(js.JSON.parse("{\"message\":\"m\"}")), ErrorEntry("m"))
      intercept[RuntimeException](converter.fromNative(js.JSON.parse("{\"path\":\"p\"}")))
      intercept[RuntimeException](converter.fromNative(js.JSON.parse("{\"message\":\"m\",\"path\":1}")))
      intercept[RuntimeException](converter.fromNative(js.JSON.parse("\"m\"")))
      intercept[RuntimeException](converter.fromNative(js.undefined))
    }

    test("HandlerOptions.default: data envelope, errors list, no defaults, 1 MiB") {
      val options = HandlerOptions.default
      assertEquals(options.success, SuccessEnvelope.Data)
      assertEquals(options.errors, ErrorEnvelope.List)
      assertEquals(options.defaults, ResponseHead.empty)
      assertEquals(options.bodyLimit, BodyLimit(1L << 20))
      assertEquals(HandlerOptions(), options)
    }

    test("Secret hides its value and compares without early exit") {
      val secret = Secret("s3cr3t-value")
      assertEquals(secret.toString, "Secret(<redacted>)")
      assertEquals(s"$secret", "Secret(<redacted>)")
      assert(secret.matches("s3cr3t-value"))
      assert(!secret.matches("s3cr3t-valuE"))
      assert(!secret.matches("s3cr3t"))
      assert(!secret.matches("s3cr3t-value-longer"))
      assert(!secret.matches(""))
      assert(Secret("").matches(""))
      assert(!Secret("").matches("x"))
      assertEquals(secret.reveal, "s3cr3t-value")
      assertEquals(secret, Secret("s3cr3t-value"))
      assertNotEquals(secret, Secret("other"))
    }

    test("Secret decodes only JavaScript strings") {
      val converter = summon[NativeConverter[Secret]]
      assert(converter.fromNative("token": js.Any).matches("token"))
      intercept[RuntimeException](converter.fromNative(42: js.Any))
      assertEquals(converter.toNative(Secret("x")).asInstanceOf[String], "x")
    }

    test("Unit converts to and from undefined only") {
      val converter = summon[NativeConverter[Unit]]
      assert(js.isUndefined(converter.toNative(())))
      assertEquals(converter.fromNative(js.undefined), ())
      intercept[RuntimeException](converter.fromNative(null: js.Any))
    }
end ValuesSuite
