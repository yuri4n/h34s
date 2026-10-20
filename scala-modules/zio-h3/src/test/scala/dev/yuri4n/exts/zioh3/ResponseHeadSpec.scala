package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.zioh3.ZioTesting.*
import org.getshaka.nativeconverter.NativeConverter
import zio.*
import zio.test.*

import scala.scalajs.js

/** The response head as ZIO `ZState` (C12) and the handler options (4.2b) for ZIO handlers. */
object ResponseHeadSpec extends ZIOSpecDefault:
    final case class Greeting(message: String) derives NativeConverter
    final case class Input(name: String) derives NativeConverter

    def spec = suite("ZIO response head and options")(
      test("deleteCookie sends Secure, can match a domain, and can be kept on failure") {
        val ok = Call()
        val failed = Call()
        for
          _ <- ok.invoke(ZHandler.define(_ =>
            Response.deleteCookie("__Host-session") *> Response.deleteCookie("wide", domain = Some("example.com")).as(Greeting("bye")),
          ))
          _ <- failed.invoke(ZHandler.define[String](_ =>
            Response.deleteCookie("session", onFailure = OnFailure.Keep) *> ZIO.fail(HttpError.unauthorized("Sign in again")),
          ))
        yield
          val cookies = ok.response.headerValues("set-cookie")
          assertTrue(
            cookies.exists(c => c.startsWith("__Host-session=") && c.contains("Max-Age=0") && c.contains("Secure")),
            cookies.exists(c => c.startsWith("wide=") && c.contains("Domain=example.com")),
            failed.response.status == 401,
            failed.response.headerValues("set-cookie").exists(_.startsWith("session=")),
          )
      },
      test("success applies the status, headers in order and cookies once the program finished") {
        var headersDuringProgram: List[String] = List("unset")
        val call = Call()
        val handler = ZHandler.define { _ =>
          for
            _ <- Response.status(Status.Created)
            _ <- Response.header("X-Id", "7")
            _ <- Response.appendHeader("Vary", "Accept")
            _ <- Response.appendHeader("Vary", "Origin")
            _ <- Response.header("X-Replaced", "old")
            _ <- Response.header("X-Replaced", "new")
            _ <- Response.cookie(SetCookie("session", "a b", maxAge = Some(60)))
            _ <- Response.deleteCookie("legacy")
            head <- Response.head
            _ <- ZIO.succeed { headersDuringProgram = call.response.headerNames }
          yield Greeting(s"${head.headers.size} headers")
        }
        for body <- call.invoke(handler)
        yield
          val cookies = call.response.headerValues("set-cookie")
          assertTrue(
            headersDuringProgram == Nil,
            call.response.status == 201,
            data(body).message.asInstanceOf[String] == "5 headers",
            call.response.header("x-id").contains("7"),
            call.response.headerValues("vary") == List("Accept", "Origin"),
            call.response.header("x-replaced").contains("new"),
            cookies.size == 2,
            cookies.exists(c => c.startsWith("session=a%20b") && c.contains("Max-Age=60") && c.contains("HttpOnly") && c.contains("Secure")),
            cookies.exists(c => c.startsWith("legacy=") && c.contains("Max-Age=0")),
          )
      },
      test("a failure discards the program's status, headers and cookies unless marked Keep") {
        def handler(failure: IO[Throwable, String]) = ZHandler.define[String] { _ =>
          Response.status(Status.Created) *>
            Response.header("x-discarded", "1") *>
            Response.header("x-request-id", "abc", OnFailure.Keep) *>
            Response.cookie(SetCookie("session", "abc")) *>
            failure
        }
        val safe = Call()
        val defect = Call()
        for
          safeBody <- safe.invoke(handler(ZIO.fail(HttpError.unauthorized("Sign in").header("WWW-Authenticate", "Bearer"))))
          defectBody <- defect.invoke(handler(ZIO.die(IllegalStateException("private"))))
        yield assertTrue(
          safe.response.status == 401,
          wire(safeBody) == errors("Sign in"),
          safe.response.header("www-authenticate").contains("Bearer"),
          safe.response.header("x-request-id").contains("abc"),
          safe.response.header("x-discarded").isEmpty,
          safe.response.header("set-cookie").isEmpty,
          defect.response.status == 500,
          wire(defectBody) == opaque,
          defect.response.header("x-request-id").contains("abc"),
          defect.response.header("x-discarded").isEmpty,
          defect.response.header("set-cookie").isEmpty,
        )
      },
      test("changes made before catchAll are not rolled back") {
        val call = Call()
        val handler = ZHandler.define { _ =>
          (Response.header("x-attempt", "1") *> ZIO.fail(HttpError.conflict("busy"))).catchAll(_ => ZIO.succeed("recovered"))
        }
        for body <- call.invoke(handler)
        yield assertTrue(data(body).asInstanceOf[String] == "recovered", call.response.header("x-attempt").contains("1"))
      },
      test("a forked fiber works on a copy of the head; joining it copies the child's head back") {
        val joined = Call()
        val detached = Call()
        for
          _ <- joined.invoke(ZHandler.define(_ => Response.header("x-child", "1").fork.flatMap(_.join).as("ok")))
          _ <- detached.invoke(ZHandler.define { _ =>
            for
              started <- Promise.make[Nothing, Unit]
              child <- (Response.header("x-child", "1") *> started.succeed(()) *> ZIO.never).fork
              _ <- started.await
              _ <- child.interrupt
            yield "ok"
          })
        yield assertTrue(joined.response.header("x-child").contains("1"), detached.response.header("x-child").isEmpty)
      },
      test("HandlerOptions defaults apply on success and on failure") {
        val options = HandlerOptions(defaults = ResponseHead.noStore.withHeader("x-app", "notes"))
        val ok = Call()
        val failed = Call()
        for
          _ <- ok.invoke(ZHandler.define(options)(_ => ZIO.succeed(Greeting("ok"))))
          _ <- failed.invoke(ZHandler.define(options)(_ => ZIO.fail(HttpError.notFound("No such note")).as("never")))
        yield assertTrue(
          ok.response.header("cache-control").contains("no-store"),
          ok.response.header("x-app").contains("notes"),
          failed.response.status == 404,
          failed.response.header("cache-control").contains("no-store"),
          failed.response.header("x-app").contains("notes"),
        )
      },
      test("redirect sets Location and a 3xx status; Unit keeps a status the handler set") {
        val redirect = Call()
        val created = Call()
        for
          redirectBody <- redirect.invoke(ZHandler.define(_ => Response.redirect("/login")))
          createdBody <- created.invoke(ZHandler.define(_ => Response.status(Status.Created) *> Response.header("x-id", "7")))
        yield assertTrue(
          redirectBody == null, redirect.response.status == 302, redirect.response.header("location").contains("/login"),
          createdBody == null, created.response.status == 201, created.response.header("x-id").contains("7"),
        )
      },
      test("invalid header names, header values and redirect statuses are defects") {
        val name = Call()
        val value = Call()
        val redirect = Call()
        for
          _ <- name.invoke(ZHandler.define(_ => Response.header("bad name", "x").as("never")))
          _ <- value.invoke(ZHandler.define(_ => Response.header("x-ok", "line\r\nbreak").as("never")))
          _ <- redirect.invoke(ZHandler.define(_ => Response.redirect("/x", Status.Ok)))
        yield assertTrue(
          name.response.status == 500, value.response.status == 500, value.response.header("x-ok").isEmpty,
          redirect.response.status == 500,
        )
      },
      test("Data wraps value bodies; Raw sends text as text and other values as JSON; SingleOrList sends one error as error") {
        val raw = HandlerOptions(success = SuccessEnvelope.Raw)
        val single = HandlerOptions(errors = ErrorEnvelope.SingleOrList)
        val calls = List.fill(5)(Call())
        for
          dataModel <- calls(0).invoke(ZHandler.define(_ => ZIO.succeed(Greeting("hi"))))
          rawModel <- calls(1).invoke(ZHandler.define(raw)(_ => ZIO.succeed(Greeting("hi"))))
          rawText <- calls(2).invoke(ZHandler.define(raw)(_ => ZIO.succeed("hi")))
          singleOne <- calls(3).invoke(ZHandler.define(single)(_ => ZHandler.failWith(404, "No such note").as("never")))
          singleSeveral <- calls(4).invoke(ZHandler.define(single)(_ => ZHandler.failWith(400, "a", "b").as("never")))
        yield assertTrue(
          wire(dataModel) == "{\"data\":{\"message\":\"hi\"}}",
          rawModel.asInstanceOf[String] == "{\"message\":\"hi\"}",
          calls(1).response.header("content-type").contains("application/json"),
          rawText.asInstanceOf[String] == "hi",
          calls(2).response.header("content-type").contains("text/plain; charset=utf-8"),
          wire(singleOne) == "{\"error\":{\"message\":\"No such note\"}}",
          wire(singleSeveral) == errors("a", "b"),
        )
      },
      test("HandlerOptions.bodyLimit is the default limit of json and text reads") {
        val options = HandlerOptions(bodyLimit = BodyLimit(16))
        val large = "{\"name\":\"" + ("x" * 32) + "\"}"
        val limited = post(large)
        for
          tooLarge <- limited.invoke(ZHandler.define(options)(_.body.json[Input].map(_.name)))
          explicit <- post(large).invoke(ZHandler.define(options)(_.body.json[Input](BodyLimit(1024)).map(_.name.length)))
        yield assertTrue(
          limited.response.status == 413, wire(tooLarge) == errors("Request body is too large"),
          data(explicit).asInstanceOf[Int] == 32,
        )
      },
      // C13
      // claude 2026-10-20: changed: ZHandler.test replaces the manual ZState and Scope provisioning (ADR-0006, Q10)
      test("a handler function is testable without h3: Request.fake and ZHandler.test") {
        val handler: Request => ZIO[HandlerEnv, HttpError | InvalidInput, Greeting] = request =>
          for
            first <- request.body.json[Input]
            again <- request.body.text()
            _ <- Response.status(Status.Created)
          yield Greeting(s"${first.name} $again")
        // claude 2026-10-19: changed: the fake request sends a JSON content type (issue #41)
        val request = Request.fake(method = "POST", headers = Map("content-type" -> "application/json"), body = Some("{\"name\":\"Scala\"}"))
        for response <- ZHandler.test(handler)(request)
        yield assertTrue(
          response.status.contains(Status.Created),
          response.body == TestResponse.Body.Value(Greeting("Scala {\"name\":\"Scala\"}")),
        )
      },
    ) @@ TestAspect.timeout(45.seconds)
end ResponseHeadSpec
