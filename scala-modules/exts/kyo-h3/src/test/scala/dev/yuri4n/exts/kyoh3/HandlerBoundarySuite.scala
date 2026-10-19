package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.kyoh3.KyoTesting.*
import kyo.*
import munit.FunSuite
import org.getshaka.nativeconverter.{NativeConverter, ParseState}

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js

class HandlerBoundarySuite extends FunSuite:
    given ExecutionContext = ExecutionContext.global
    // The first mapped exception loads the large Scala.js test source map.
    override val munitTimeout: FiniteDuration = 60.seconds

    final case class Greeting(message: String) derives NativeConverter
    final case class Rejected(message: String) extends SafeError:
        override def status: Int = 422

    // K1
    test("converts a model and passes the request snapshot to the handler function") {
      val call = Call()
      call.invoke(KHandler.define(request => Greeting(request.path))).map { body =>
        assertEquals(call.response.status, 200)
        assertEquals(json(body), "{\"data\":{\"message\":\"/test\"}}")
        assert(!call.response.hasListener)
      }
    }

    // K2
    test("application Env provisioning wraps the handler outside the transport boundary") {
      final case class Config(prefix: String)
      val program: Request => Greeting < (HandlerEffects & Env[Config]) =
        request => Env.get[Config].map(config => Greeting(config.prefix + request.path))
      Call().invoke(KHandler.define(request => Env.run(Config("hello"))(program(request)))).map { body =>
        assertEquals(data(body).message.asInstanceOf[String], "hello/test")
      }
    }

    // K3, C2
    test("a reusable handler runs once per request with its own snapshot") {
      var evaluations = 0
      val gate = Promise[Unit]()
      val handler = KHandler.define { (request: Request) =>
        evaluations += 1
        Async.fromFuture(gate.future).map(_ => Greeting(request.path))
      }
      assertEquals(evaluations, 0)
      val first = Call("/first").invoke(handler)
      val second = Call("/second").invoke(handler)
      gate.success(())
      first.zip(second).map { case (a, b) =>
        assertEquals(evaluations, 2)
        assertEquals(data(a).message.asInstanceOf[String], "/first")
        assertEquals(data(b).message.asInstanceOf[String], "/second")
      }
    }

    // K4
    test("Unit is a 204 without a body, without importing a converter") {
      val call = Call()
      call.invoke(KHandler.define(_ => Sync.defer(()))).map { body =>
        // h3 sends a `null` result with sendNoContent (see HttpHandlerSuite for the wire).
        assert(body == null && !js.isUndefined(body), s"expected null, got $body")
        assertEquals(call.response.status, 204)
      }
    }

    // K5
    test("safe failures and explicit failures keep approved messages and statuses, also in development") {
      val safe = Call(development = true)
      val explicit = Call(development = true)
      for
        safeBody <- safe.invoke(KHandler.define[String](_ => Abort.fail(Rejected("Invalid name"))))
        explicitBody <- explicit.invoke(KHandler.define[String](_ => KHandler.failWith(409, "Already exists")))
      yield
        assertEquals(safe.response.status, 422)
        assertEquals(json(safeBody), errors("Invalid name"))
        assertEquals(explicit.response.status, 409)
        assertEquals(json(explicitBody), errors("Already exists"))
    }

    // C3
    test("InvalidInput renders one entry per detail with its path, or one generic entry") {
      val detailed = Call()
      val generic = Call(development = true)
      val entries = List(ErrorEntry("must not be shorter than 3", Some("name")), ErrorEntry("is required", Some("age")), ErrorEntry("at least one field"))
      for
        detailedBody <- detailed.invoke(KHandler.define[String](_ => Abort.fail(InvalidInput(entries))))
        genericBody <- generic.invoke(KHandler.define[String](_ => Abort.fail(InvalidInput())))
      yield
        assertEquals(detailed.response.status, 422)
        assertEquals(
          json(detailedBody),
          "{\"errors\":[{\"message\":\"must not be shorter than 3\",\"path\":\"name\"}," +
            "{\"message\":\"is required\",\"path\":\"age\"},{\"message\":\"at least one field\"}]}",
        )
        assertEquals(generic.response.status, 422)
        assertEquals(json(genericBody), errors("Request body does not match the expected shape"))
    }

    test("a SafeError with a non-error status is a defect") {
      final case class Odd(message: String) extends SafeError:
        override def status: Int = 200
      val call = Call()
      call.invoke(KHandler.define[String](_ => Abort.fail(Odd("looks fine")))).map { body =>
        assertEquals(call.response.status, 500)
        assertOpaque(body)
      }
    }

    // K6, C4
    test("typed failures, panics, thrown SafeErrors and construction exceptions are opaque in production") {
      val handlers = List(
        KHandler.define[String](_ => Abort.fail(new Exception("private typed failure"))),
        KHandler.define[String](_ => Sync.defer(throw new Exception("private panic"))),
        KHandler.define[String](_ => Sync.defer(throw Rejected("unapproved panic message"))),
        KHandler.define[String](_ => throw new Exception("private construction failure")),
      )
      Future.traverse(handlers) { handler =>
        val call = Call()
        call.invoke(handler).map { body =>
          assertEquals(call.response.status, 500)
          assertOpaque(body)
          assert(!call.response.hasListener)
        }
      }.map(_ => ())
    }

    // K7
    test("development diagnostics contain original and nested errors") {
      val call = Call(development = true)
      call.invoke(KHandler.define[String](_ => Abort.fail(new Exception("operation failed", new Exception("nested detail"))))).map { body =>
        assertEquals(call.response.status, 500)
        val entry = body.asInstanceOf[js.Dynamic].errors.asInstanceOf[js.Array[js.Dynamic]](0)
        assertEquals(entry.message.asInstanceOf[String], "Internal server error")
        val cause = entry.debug.cause.asInstanceOf[String]
        assert(cause.contains("operation failed"))
        assert(cause.contains("nested detail"))
        assert(cause.contains("Exception"))
      }
    }

    // K8, C5
    test("only boolean true in server context enables diagnostics, without leaking across requests") {
      val handler = KHandler.define[String](_ => Abort.fail(new Exception("private detail")))
      for
        _ <- Call(development = true).invoke(handler)
        _ <- Future.traverse(List[js.Any](false, "true", 1, js.undefined)) { mode =>
          val call = Call(development = mode, headers = Seq("x-h34s-development" -> "true"))
          call.invoke(handler).map { body => assertEquals(call.response.status, 500); assertOpaque(body) }
        }
      yield ()
    }

    // K9, C6
    test("request finalizers run once before successful and failed responses settle") {
      var releases = 0
      def resource = Scope.acquireRelease(Sync.defer(()))(_ => Sync.defer { releases += 1 })
      for
        _ <- Call().invoke(KHandler.define(_ => resource.map(_ => Greeting("ok"))))
        _ = assertEquals(releases, 1)
        _ <- Call().invoke(KHandler.define[String](_ => resource.map(_ => Abort.fail(new Exception("failure")))))
        _ = assertEquals(releases, 2)
        _ <- Call().invoke(KHandler.define[String](_ => resource.map(_ => Sync.defer(throw new Exception("panic")))))
      yield assertEquals(releases, 3)
    }

    // claude 2026-10-19: created: Kyo side of issue #40 (a failing finalizer keeps the computed response); changed: 204, discarded header and development cases (review)
    test("a failing request-scope finalizer keeps the computed response: value, Unit and safe failure") {
      def withFailingFinalizer[B](outcome: => B < HandlerEffects): B < HandlerEffects =
        for
          _ <- Scope.ensure(Sync.defer(throw new Exception("kyo cleanup failure")))
          _ <- Response.status(Status.Created)
          _ <- Response.header("X-Kept", "yes")
          result <- outcome
        yield result
      val value = Call()
      val empty = Call()
      val rejected = Call()
      val developmentRejected = Call(development = true)
      val noContent = Call()
      def rejecting = KHandler.define[String](_ => withFailingFinalizer(Abort.fail(Rejected("Invalid name"))))
      for
        valueBody <- value.invoke(KHandler.define(_ => withFailingFinalizer(Greeting("ok"))))
        _ <- empty.invoke(KHandler.define(_ => withFailingFinalizer(())))
        rejectedBody <- rejected.invoke(rejecting)
        developmentBody <- developmentRejected.invoke(rejecting)
        noContentBody <- noContent.invoke(KHandler.define(_ =>
          Scope.ensure(Sync.defer(throw new Exception("kyo cleanup failure"))),
        ))
      yield
        assertEquals(value.response.status, 201)
        assertEquals(json(valueBody), "{\"data\":{\"message\":\"ok\"}}")
        assertEquals(value.response.header("x-kept"), Some("yes"))
        assertEquals(empty.response.status, 201, "a Unit body keeps the status the program set")
        assertEquals(empty.response.header("x-kept"), Some("yes"))
        assertEquals(rejected.response.status, 422)
        assertEquals(json(rejectedBody), KyoTesting.errors("Invalid name"))
        assertEquals(rejected.response.header("x-kept"), None)
        assertEquals(developmentRejected.response.status, 422)
        assertEquals(json(developmentBody), KyoTesting.errors("Invalid name"), "no diagnostics on a safe failure")
        assertEquals(noContent.response.status, 204)
        assert(noContentBody == null, s"expected no body, got $noContentBody")
    }

    // K10
    test("a throwing native converter produces an opaque response after request cleanup") {
      final case class Broken(value: String)
      given NativeConverter[Broken]:
        extension (value: Broken) def toNative: js.Any = throw Rejected("private converter detail")
        def fromNative(state: ParseState): Broken = Broken("")
      var releases = 0
      val call = Call()
      call.invoke(KHandler.define(_ => Scope.ensure(Sync.defer { releases += 1 }).map(_ => Broken("value")))).map { body =>
        assertEquals(releases, 1)
        assertEquals(call.response.status, 500)
        assertOpaque(body)
      }
    }

    // K11
    test("asynchronous finalizers finish before success, safe failure, and panic settle") {
      val outcomes: List[(Int, TaskHandler[String])] = List(
        (200, Sync.defer("ok")),
        (422, Abort.fail(Rejected("Invalid name"))),
        (500, Sync.defer(throw new Exception("private panic"))),
      )
      Future.traverse(outcomes) { case (status, outcome) =>
        val finalizing = Promise[Unit]()
        val release = Promise[Unit]()
        var releases = 0
        val call = Call()
        val running = call.invoke(KHandler.define { _ =>
          Scope.ensure(
            Sync.defer { finalizing.success(()); () }
              .map(_ => Async.fromFuture(release.future))
              .map(_ => Sync.defer { releases += 1 }),
          ).map(_ => outcome)
        })
        val checked = for
          _ <- finalizing.future
          _ <- nextTurn
          _ = assert(!running.isCompleted, s"status $status settled before finalization")
          _ = release.success(())
          _ <- running
        yield
          assertEquals(call.response.status, status)
          assertEquals(releases, 1)
          assert(!call.response.hasListener)
        TestDeadline(checked).andThen { case _ => release.trySuccess(()) }
      }.map(_ => ())
    }

    // K12, C7
    test("disconnect interrupts pending work and waits for asynchronous cleanup") {
      val acquired = Promise[Unit]()
      val finalizing = Promise[Unit]()
      val release = Promise[Unit]()
      var releases = 0
      val call = Call()
      val handler = KHandler.define[String] { _ =>
        Scope.acquireRelease(Sync.defer { acquired.success(()); () }) { _ =>
          Sync.defer { finalizing.success(()); () }
            .map(_ => Async.fromFuture(release.future))
            .map(_ => Sync.defer { releases += 1 })
        }.map(_ => Async.never[String])
      }
      val running = call.invoke(handler)
      val checked = for
        _ <- acquired.future
        _ = call.response.disconnect()
        _ <- finalizing.future
        _ = assert(!running.isCompleted, "the response must await cleanup after interruption")
        _ = release.success(())
        body <- running
      yield
        assert(js.isUndefined(body))
        assertEquals(releases, 1)
        assert(!call.response.hasListener)
        assert(call.handled, "h3 must not manufacture a 404 for a disconnected request")
        assertEquals(call.response.writes, 0)
        assertEquals(call.response.headerNames, Nil)
        call.response.disconnect()
        assertEquals(releases, 1)
      TestDeadline(checked).andThen { case _ => release.trySuccess(()) }
    }

    // K13
    test("an already closed response never starts the request program") {
      var started = false
      val call = Call()
      call.response.disconnect()
      call.invoke(KHandler.define(_ => Sync.defer { started = true; Greeting("unexpected") })).map { body =>
        assert(js.isUndefined(body))
        assert(!started)
        assert(call.handled)
        assert(!call.response.hasListener)
      }
    }

    // K14
    test("normal completion removes the disconnect listener and late close cannot repeat cleanup") {
      var releases = 0
      val call = Call()
      call.invoke(KHandler.define(_ => Scope.ensure(Sync.defer { releases += 1 }).map(_ => Greeting("ok")))).map { _ =>
        assertEquals(releases, 1)
        assert(!call.response.hasListener)
        call.response.disconnect()
        assertEquals(releases, 1)
      }
    }

    // C12
    test("success applies the status, headers in order, and cookies once the program finished") {
      var headersDuringProgram: List[String] = Nil
      val call = Call()
      val handler = KHandler.define { _ =>
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
          _ <- Sync.defer { headersDuringProgram = call.response.headerNames }
        yield Greeting(s"${head.headers.size} headers")
      }
      call.invoke(handler).map { body =>
        assertEquals(headersDuringProgram, Nil, "the native response must not change while the program runs")
        assertEquals(call.response.status, 201)
        assertEquals(data(body).message.asInstanceOf[String], "5 headers")
        assertEquals(call.response.header("x-id"), Some("7"))
        assertEquals(call.response.headerValues("vary"), List("Accept", "Origin"))
        assertEquals(call.response.header("x-replaced"), Some("new"))
        val cookies = call.response.headerValues("set-cookie")
        assertEquals(cookies.size, 2)
        assert(cookies.exists(c => c.startsWith("session=a%20b") && c.contains("Max-Age=60") && c.contains("HttpOnly") && c.contains("Secure") && c.contains("SameSite=Lax") && c.contains("Path=/")), cookies.toString)
        assert(cookies.exists(c => c.startsWith("legacy=") && c.contains("Max-Age=0")), cookies.toString)
      }
    }

    test("a failure discards the program's status, headers and cookies unless marked Keep") {
      def handler(failure: TaskHandler[String]) = KHandler.define[String] { _ =>
        Response.status(Status.Created)
          .andThen(Response.header("x-discarded", "1"))
          .andThen(Response.header("x-request-id", "abc", OnFailure.Keep))
          .andThen(Response.cookie(SetCookie("session", "abc")))
          .andThen(failure)
      }
      val safe = Call()
      val defect = Call()
      for
        safeBody <- safe.invoke(handler(Abort.fail(HttpError.unauthorized("Sign in").header("WWW-Authenticate", "Bearer"))))
        defectBody <- defect.invoke(handler(Sync.defer(throw IllegalStateException("private"))))
      yield
        assertEquals(safe.response.status, 401)
        assertEquals(json(safeBody), errors("Sign in"))
        assertEquals(safe.response.header("www-authenticate"), Some("Bearer"))
        assertEquals(safe.response.header("x-request-id"), Some("abc"))
        assertEquals(safe.response.header("x-discarded"), None)
        assertEquals(safe.response.header("set-cookie"), None)
        assertEquals(defect.response.status, 500)
        assertOpaque(defectBody)
        assertEquals(defect.response.header("x-request-id"), Some("abc"))
        assertEquals(defect.response.header("x-discarded"), None)
        assertEquals(defect.response.header("set-cookie"), None)
    }

    test("defaults apply on success and on failure") {
      val defaults = ResponseHead.noStore.withHeader("x-app", "notes")
      val ok = Call()
      val failed = Call()
      for
        _ <- ok.invoke(KHandler.define(HandlerOptions(defaults = defaults))(_ => Greeting("ok")))
        _ <- failed.invoke(KHandler.define[String](HandlerOptions(defaults = defaults))(_ => Abort.fail(HttpError.notFound("No such note"))))
      yield
        assertEquals(ok.response.header("cache-control"), Some("no-store"))
        assertEquals(ok.response.header("x-app"), Some("notes"))
        assertEquals(failed.response.status, 404)
        assertEquals(failed.response.header("cache-control"), Some("no-store"))
        assertEquals(failed.response.header("x-app"), Some("notes"))
    }

    test("redirect sets Location and a 3xx status") {
      val call = Call()
      call.invoke(KHandler.define(_ => Response.redirect("/login"))).map { body =>
        assert(body == null && !js.isUndefined(body), "a Unit redirect has no body")
        assertEquals(call.response.status, 302)
        assertEquals(call.response.header("location"), Some("/login"))
      }
    }

    test("invalid header names and values are defects reported at the call") {
      val name = Call()
      val value = Call()
      for
        _ <- name.invoke(KHandler.define(_ => Response.header("bad name", "x").andThen(Greeting("never"))))
        _ <- value.invoke(KHandler.define(_ => Response.header("x-ok", "line\r\nbreak").andThen(Greeting("never"))))
      yield
        assertEquals(name.response.status, 500)
        assertEquals(value.response.status, 500)
        assertEquals(value.response.header("x-ok"), None)
    }

    // 4.2b wire format
    test("building an invalid Response value does not throw; running it is a defect") {
      val invalid = Response.header("bad name", "x")
      val badCookie = Response.deleteCookie("bad;name")
      val call = Call()
      val cookieCall = Call()
      for
        _ <- call.invoke(KHandler.define(_ => invalid.andThen(Greeting("never"))))
        _ <- cookieCall.invoke(KHandler.define(_ => badCookie.andThen(Greeting("never"))))
      yield
        assertEquals(call.response.status, 500)
        assertEquals(cookieCall.response.status, 500)
    }

    test("deleteCookie sends Secure, can match a domain, and can be kept on failure") {
      val ok = Call()
      val failed = Call()
      for
        _ <- ok.invoke(KHandler.define { _ =>
          Response.deleteCookie("__Host-session")
            .andThen(Response.deleteCookie("wide", domain = Some("example.com")))
            .andThen(Greeting("bye"))
        })
        _ <- failed.invoke(KHandler.define[String] { _ =>
          Response.deleteCookie("session", onFailure = OnFailure.Keep).andThen(Abort.fail(HttpError.unauthorized("Sign in again")))
        })
      yield
        val cookies = ok.response.headerValues("set-cookie")
        assert(cookies.exists(c => c.startsWith("__Host-session=") && c.contains("Max-Age=0") && c.contains("Path=/") && c.contains("Secure")), cookies.toString)
        assert(cookies.exists(c => c.startsWith("wide=") && c.contains("Domain=example.com") && c.contains("Max-Age=0")), cookies.toString)
        assertEquals(failed.response.status, 401)
        assert(failed.response.headerValues("set-cookie").exists(c => c.startsWith("session=") && c.contains("Max-Age=0")))
    }

    test("Data wraps value bodies in data; Raw sends text as text and other values as JSON") {
      val raw = HandlerOptions(success = SuccessEnvelope.Raw)
      final case class Absent()
      given NativeConverter[Absent]:
        extension (value: Absent) def toNative: js.Any = js.undefined
        def fromNative(state: ParseState): Absent = Absent()
      val calls = List.fill(5)(Call())
      for
        dataModel <- calls(0).invoke(KHandler.define(_ => Greeting("hi")))
        dataText <- calls(1).invoke(KHandler.define(_ => "hi"))
        rawModel <- calls(2).invoke(KHandler.define(raw)(_ => Greeting("hi")))
        rawText <- calls(3).invoke(KHandler.define(raw)(_ => "hi"))
        rawUndefined <- calls(4).invoke(KHandler.define(raw)(_ => Absent()))
      yield
        assertEquals(json(dataModel), "{\"data\":{\"message\":\"hi\"}}")
        assertEquals(json(dataText), "{\"data\":\"hi\"}")
        assertEquals(rawModel.asInstanceOf[String], "{\"message\":\"hi\"}")
        assertEquals(calls(2).response.header("content-type"), Some("application/json"))
        assertEquals(rawText.asInstanceOf[String], "hi")
        assertEquals(calls(3).response.header("content-type"), Some("text/plain; charset=utf-8"))
        assertEquals(calls(3).response.header("x-content-type-options"), Some("nosniff"))
        assert(rawUndefined == null && !js.isUndefined(rawUndefined), "Raw undefined is sent as null (204)")
        calls.take(4).foreach(call => assertEquals(call.response.status, 200))
    }

    test("Unit keeps a status the handler set and its headers, with either success envelope") {
      val created = Call()
      val raw = Call()
      for
        createdBody <- created.invoke(KHandler.define(_ => Response.status(Status.Created).andThen(Response.header("x-id", "7"))))
        rawBody <- raw.invoke(KHandler.define(HandlerOptions(success = SuccessEnvelope.Raw))(_ => ()))
      yield
        assert(createdBody == null && !js.isUndefined(createdBody))
        assertEquals(created.response.status, 201)
        assertEquals(created.response.header("x-id"), Some("7"))
        assert(rawBody == null && !js.isUndefined(rawBody))
        assertEquals(raw.response.status, 204)
    }

    test("List always sends errors; SingleOrList sends error for exactly one entry and errors for several") {
      val single = HandlerOptions(errors = ErrorEnvelope.SingleOrList)
      val several = InvalidInput(List(ErrorEntry("is required", Some("name")), ErrorEntry("is required", Some("age"))))
      val calls = List.fill(6)(Call())
      for
        listOne <- calls(0).invoke(KHandler.define[String](_ => Abort.fail(HttpError.notFound("No such note"))))
        listSeveral <- calls(1).invoke(KHandler.define[String](_ => Abort.fail(several)))
        singleOne <- calls(2).invoke(KHandler.define[String](single)(_ => Abort.fail(HttpError.notFound("No such note"))))
        singleSeveral <- calls(3).invoke(KHandler.define[String](single)(_ => Abort.fail(several)))
        singleGeneric <- calls(4).invoke(KHandler.define[String](single)(_ => Abort.fail(InvalidInput())))
        singleInternal <- calls(5).invoke(KHandler.define[String](single)(_ => Abort.fail(new Exception("private"))))
      yield
        val severalJson = "[{\"message\":\"is required\",\"path\":\"name\"},{\"message\":\"is required\",\"path\":\"age\"}]"
        assertEquals(json(listOne), errors("No such note"))
        assertEquals(json(listSeveral), s"{\"errors\":$severalJson}")
        assertEquals(json(singleOne), "{\"error\":{\"message\":\"No such note\"}}")
        assertEquals(json(singleSeveral), s"{\"errors\":$severalJson}")
        assertEquals(json(singleGeneric), "{\"error\":{\"message\":\"Request body does not match the expected shape\"}}")
        assertEquals(json(singleInternal), "{\"error\":{\"message\":\"Internal server error\"}}")
        assertEquals(calls.map(_.response.status), List(404, 422, 404, 422, 422, 500))
    }

    test("a SafeError may send several entries; an error without valid entries is a defect") {
      final case class Conflicts(message: String) extends SafeError:
        override def status: Int = 409
        override def errors: List[ErrorEntry] = List(ErrorEntry("slug is taken", Some("slug")), ErrorEntry(message))
      final case class Empty(message: String) extends SafeError:
        override def errors: List[ErrorEntry] = Nil
      final case class NullPath(message: String) extends SafeError:
        override def errors: List[ErrorEntry] = List(ErrorEntry(message, Some(null)))
      val conflict = Call()
      val empty = Call()
      val nullPath = Call()
      for
        conflictBody <- conflict.invoke(KHandler.define[String](_ => Abort.fail(Conflicts("title is taken"))))
        emptyBody <- empty.invoke(KHandler.define[String](_ => Abort.fail(Empty("private"))))
        nullPathBody <- nullPath.invoke(KHandler.define[String](_ => Abort.fail(NullPath("private"))))
      yield
        assertEquals(conflict.response.status, 409)
        assertEquals(json(conflictBody), "{\"errors\":[{\"message\":\"slug is taken\",\"path\":\"slug\"},{\"message\":\"title is taken\"}]}")
        assertEquals(empty.response.status, 500)
        assertOpaque(emptyBody)
        assertEquals(nullPath.response.status, 500)
        assertOpaque(nullPathBody)
    }

    test("debug mode adds diagnostics like development; without both flags the 500 stays opaque") {
      val handler = KHandler.define[String](_ => Abort.fail(new Exception("private detail")))
      val single = KHandler.define[String](HandlerOptions(errors = ErrorEnvelope.SingleOrList))(_ => Abort.fail(new Exception("private detail")))
      def cause(entry: js.Dynamic): String = entry.debug.cause.asInstanceOf[String]
      for
        debug <- Call(development = false, debug = true).invoke(handler)
        debugSingle <- Call(development = false, debug = true).invoke(single)
        neither <- Call(development = false, debug = false).invoke(handler)
        stringFlag <- Call(development = false, debug = "true").invoke(handler)
        forged <- Call(headers = Seq("x-h34s-debug" -> "true"), path = "/test?debug=true").invoke(handler)
      yield
        assert(cause(debug.asInstanceOf[js.Dynamic].errors.asInstanceOf[js.Array[js.Dynamic]](0)).contains("private detail"))
        assertEquals(debug.asInstanceOf[js.Dynamic].errors.asInstanceOf[js.Array[js.Dynamic]](0).message.asInstanceOf[String], "Internal server error")
        assert(cause(debugSingle.asInstanceOf[js.Dynamic].error.asInstanceOf[js.Dynamic]).contains("private detail"))
        assertOpaque(neither)
        assertOpaque(stringFlag)
        assertOpaque(forged)
    }

    test("approved errors never carry diagnostics, even in development and debug mode") {
      val call = Call(development = true, debug = true)
      call.invoke(KHandler.define[String](_ => Abort.fail(HttpError.conflict("Already exists")))).map { body =>
        assertEquals(json(body), errors("Already exists"))
      }
    }

    test("the request snapshot exposes the plugin's debug flag") {
      val handler = KHandler.define(request => s"${request.development} ${request.debug}")
      for
        debug <- Call(development = false, debug = true).invoke(handler)
        both <- Call(development = true, debug = true).invoke(handler)
        none <- Call().invoke(handler)
      yield
        assertEquals(data(debug).asInstanceOf[String], "false true")
        assertEquals(data(both).asInstanceOf[String], "true true")
        assertEquals(data(none).asInstanceOf[String], "false false")
    }

    test("HandlerOptions.bodyLimit is the default limit of json and text reads; an explicit limit wins") {
      final case class Input(name: String) derives NativeConverter
      val options = HandlerOptions(bodyLimit = BodyLimit(16))
      val large = Some("{\"name\":\"" + ("x" * 32) + "\"}")
      def handlerOf(read: Request => String < HandlerEffects) = KHandler.define(options)(read)
      def result(read: Request => String < HandlerEffects): Future[(Int, String)] =
        // Request.fake carries the same default limit that the snapshot of `options` carries.
        run(Scope.run(Var.run(ResponseHead.empty)(Abort.run[HttpError | InvalidInput](read(Request.fake(method = "POST", body = large, bodyLimit = options.bodyLimit))))))
          .map(_.getOrThrow.fold(value => 200 -> value, error => error.status -> error.message, error => throw error))
      val small = Call()
      small.invoke(handlerOf(_.body.text())).flatMap { smallBody =>
        assertEquals(data(smallBody).asInstanceOf[String], "")
        for
          json <- result(_.body.json[Input].map(_.name))
          text <- result(_.body.text())
          explicit <- result(_.body.json[Input](BodyLimit(1024)).map(_.name.length.toString))
          default <- run(Request.fake(body = large).body.text()).map(_.getOrThrow.length)
        yield
          assertEquals(json, 413 -> "Request body is too large")
          assertEquals(text, 413 -> "Request body is too large")
          assertEquals(explicit, 200 -> "32")
          assertEquals(default, 43, "Request.fake defaults to BodyLimit.default")
      }
    }

    // C13
    test("a handler function is testable without h3: Request.fake and Var.runTuple") {
      final case class Input(name: String) derives NativeConverter
      val handler: Request => Greeting < HandlerEffects = request =>
        for
          first <- request.body.json[Input]
          again <- request.body.text()
          _ <- Response.status(Status.Created)
        yield Greeting(s"${first.name} $again")
      val request = Request.fake(method = "POST", body = Some("{\"name\":\"Scala\"}"))
      run(Scope.run(Var.runTuple(ResponseHead.empty)(handler(request)))).map { result =>
        val (head, greeting) = result.getOrThrow
        assertEquals(head.status, Some(Status.Created))
        assertEquals(greeting, Greeting("Scala {\"name\":\"Scala\"}"))
      }
    }
end HandlerBoundarySuite
