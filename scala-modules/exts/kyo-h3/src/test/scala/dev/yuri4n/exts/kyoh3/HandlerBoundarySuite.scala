package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.kyoh3.KyoTesting.*
import kyo.*
import munit.FunSuite
import org.getshaka.nativeconverter.{NativeConverter, ParseState}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
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
        assertEquals(body.asInstanceOf[js.Dynamic].message.asInstanceOf[String], "/test")
        assert(!call.response.hasListener)
      }
    }

    // K2
    test("application Env provisioning wraps the handler outside the transport boundary") {
      final case class Config(prefix: String)
      val program: Request => Greeting < (HandlerEffects & Env[Config]) =
        request => Env.get[Config].map(config => Greeting(config.prefix + request.path))
      Call().invoke(KHandler.define(request => Env.run(Config("hello"))(program(request)))).map { body =>
        assertEquals(body.asInstanceOf[js.Dynamic].message.asInstanceOf[String], "hello/test")
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
        assertEquals(a.asInstanceOf[js.Dynamic].message.asInstanceOf[String], "/first")
        assertEquals(b.asInstanceOf[js.Dynamic].message.asInstanceOf[String], "/second")
      }
    }

    // K4
    test("Unit becomes native undefined without importing a converter") {
      Call().invoke(KHandler.define(_ => Sync.defer(()))).map(body => assert(js.isUndefined(body)))
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
        assertEquals(json(safeBody), "{\"errors\":[\"Invalid name\"]}")
        assertEquals(explicit.response.status, 409)
        assertEquals(json(explicitBody), "{\"errors\":[\"Already exists\"]}")
    }

    // C3
    test("InvalidInput renders its details, or a generic message without details") {
      val detailed = Call()
      val generic = Call(development = true)
      for
        detailedBody <- detailed.invoke(KHandler.define[String](_ => Abort.fail(InvalidInput(List("name: too short", "age: required")))))
        genericBody <- generic.invoke(KHandler.define[String](_ => Abort.fail(InvalidInput())))
      yield
        assertEquals(detailed.response.status, 422)
        assertEquals(json(detailedBody), "{\"errors\":[\"name: too short\",\"age: required\"]}")
        assertEquals(generic.response.status, 422)
        assertEquals(json(genericBody), "{\"errors\":[\"Request body does not match the expected shape\"]}")
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
        val cause = body.asInstanceOf[js.Dynamic].debug.cause.asInstanceOf[String]
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
        assertEquals(body.asInstanceOf[js.Dynamic].message.asInstanceOf[String], "5 headers")
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
        assertEquals(json(safeBody), "{\"errors\":[\"Sign in\"]}")
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
        _ <- ok.invoke(KHandler.define(defaults)(_ => Greeting("ok")))
        _ <- failed.invoke(KHandler.define[String](defaults)(_ => Abort.fail(HttpError.notFound("No such note"))))
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
        assert(js.isUndefined(body))
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
