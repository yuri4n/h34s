package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.h3.CommonEvHandler
import kyo.*
import munit.FunSuite
import org.getshaka.nativeconverter.{NativeConverter, ParseState}
import dev.yuri4n.h34s.facades.h3.mod.{EventHandlerRequest, H3Event}

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

    private final class Response(path: String = "/test", development: js.Any = js.undefined):
        private var listener: Option[js.Function0[Unit]] = None
        var writes = 0
        val native: js.Dynamic = js.Dynamic.literal(
          statusCode = 200, statusMessage = "OK", headersSent = false,
          writableEnded = false, destroyed = false, closed = false,
        )
        native.updateDynamic("once")(((name: String, callback: js.Function0[Unit]) => {
          assertEquals(name, "close")
          listener = Some(callback)
          native
        }): js.Function2[String, js.Function0[Unit], js.Dynamic])
        native.updateDynamic("on")(native.once)
        native.updateDynamic("removeListener")(((name: String, callback: js.Function0[Unit]) => {
          assertEquals(name, "close")
          if listener.contains(callback) then listener = None
          native
        }): js.Function2[String, js.Function0[Unit], js.Dynamic])
        native.updateDynamic("end")((() => { writes += 1; native.writableEnded = true; () }): js.Function0[Unit])
        native.updateDynamic("destroy")((() => disconnect()): js.Function0[Unit])
        val request = js.Dynamic.literal(method = "GET", url = path, headers = js.Dynamic.literal())
        val event = new H3Event[EventHandlerRequest](request, native)
        if !js.isUndefined(development) then
          event.context.asInstanceOf[js.Dynamic].updateDynamic("h34s")(js.Dynamic.literal(development = development))

        def status: Int = native.statusCode.asInstanceOf[Int]
        def hasListener: Boolean = listener.nonEmpty
        def disconnect(): Unit =
          native.destroyed = true
          native.closed = true
          listener.foreach(callback => callback())
        def invoke(handler: CommonEvHandler): Future[js.Any] =
          handler(event).asInstanceOf[js.Promise[js.Any]].toFuture

    private def opaque(body: js.Any): Unit =
      assertEquals(js.JSON.stringify(body), "{\"errors\":[\"Internal server error\"]}")

    private def nextTurn: Future[Unit] =
      val completed = Promise[Unit]()
      js.timers.setTimeout(0) { completed.success(()); () }
      completed.future

    test("converts a model and supplies the request through the Kyo environment") {
      val response = new Response
      response.invoke(KHandler.define(KHandler.getEvent.map(event => Greeting(event.rawEvent.path)))).map { body =>
        assertEquals(response.status, 200)
        assertEquals(body.asInstanceOf[js.Dynamic].message.asInstanceOf[String], "/test")
        assert(!response.hasListener)
      }
    }

    test("application Env provisioning remains outside the transport boundary") {
      final case class Config(prefix: String)
      val program = Env.get[Config].map(config => KHandler.getEvent.map(event => Greeting(config.prefix + event.rawEvent.path)))
      new Response().invoke(KHandler.define(Env.run(Config("hello"))(program))).map { body =>
        assertEquals(body.asInstanceOf[js.Dynamic].message.asInstanceOf[String], "hello/test")
      }
    }

    test("a reusable handler evaluates the program once per request and isolates request context") {
      var evaluations = 0
      val gate = Promise[Unit]()
      val handler = KHandler.define {
        evaluations += 1
        KHandler.getEvent.map(event => Async.fromFuture(gate.future).map(_ => Greeting(event.rawEvent.path)))
      }
      assertEquals(evaluations, 0)
      val first = new Response("/first").invoke(handler)
      val second = new Response("/second").invoke(handler)
      gate.success(())
      first.zip(second).map { case (a, b) =>
        assertEquals(evaluations, 2)
        assertEquals(a.asInstanceOf[js.Dynamic].message.asInstanceOf[String], "/first")
        assertEquals(b.asInstanceOf[js.Dynamic].message.asInstanceOf[String], "/second")
      }
    }

    test("Unit becomes native undefined without importing a converter") {
      new Response().invoke(KHandler.define(Sync.defer(()))).map(body => assert(js.isUndefined(body)))
    }

    test("safe failures and explicit failures retain approved messages and statuses") {
      val safe = new Response(development = true)
      val explicit = new Response(development = true)
      for
        safeBody <- safe.invoke(KHandler.define[String](Abort.fail(Rejected("Invalid name"))))
        explicitBody <- explicit.invoke(KHandler.define[String](KHandler.failWith(409, "Already exists")))
      yield
        assertEquals(safe.status, 422)
        assertEquals(js.JSON.stringify(safeBody), "{\"errors\":[\"Invalid name\"]}")
        assertEquals(explicit.status, 409)
        assertEquals(js.JSON.stringify(explicitBody), "{\"errors\":[\"Already exists\"]}")
    }

    test("typed failures, panics, and program construction exceptions are opaque in production") {
      val handlers = List(
        KHandler.define[String](Abort.fail(new Exception("private typed failure"))),
        KHandler.define[String](Sync.defer(throw new Exception("private panic"))),
        KHandler.define[String](Sync.defer(throw Rejected("unapproved panic message"))),
        KHandler.define[String](throw new Exception("private construction failure")),
      )
      Future.traverse(handlers) { handler =>
        val response = new Response
        response.invoke(handler).map { body =>
          assertEquals(response.status, 500)
          opaque(body)
          assert(!response.hasListener)
        }
      }.map(_ => ())
    }

    test("development diagnostics contain original and nested errors") {
      val response = new Response(development = true)
      response.invoke(KHandler.define[String](Abort.fail(new Exception("operation failed", new Exception("nested detail"))))).map { body =>
        assertEquals(response.status, 500)
        val cause = body.asInstanceOf[js.Dynamic].debug.cause.asInstanceOf[String]
        assert(cause.contains("operation failed"))
        assert(cause.contains("nested detail"))
        assert(cause.contains("Exception"))
      }
    }

    test("only boolean true in server context enables diagnostics, without leaking across requests") {
      val handler = KHandler.define[String](Abort.fail(new Exception("private detail")))
      for
        _ <- new Response(development = true).invoke(handler)
        _ <- Future.traverse(List[js.Any](false, "true", 1, js.undefined)) { mode =>
          val response = new Response(development = mode)
          response.request.headers = js.Dynamic.literal("x-h34s-development" -> "true")
          response.invoke(handler).map { body => assertEquals(response.status, 500); opaque(body) }
        }
      yield ()
    }

    test("request finalizers run once before successful and failed responses settle") {
      var releases = 0
      def resource = Scope.acquireRelease(Sync.defer(()))(_ => Sync.defer { releases += 1 })
      for
        _ <- new Response().invoke(KHandler.define(resource.map(_ => Greeting("ok"))))
        _ = assertEquals(releases, 1)
        _ <- new Response().invoke(KHandler.define[String](resource.map(_ => Abort.fail(new Exception("failure")))))
        _ = assertEquals(releases, 2)
        _ <- new Response().invoke(KHandler.define[String](resource.map(_ => Sync.defer(throw new Exception("panic")))))
      yield assertEquals(releases, 3)
    }

    test("a throwing native converter produces an opaque response after request cleanup") {
      final case class Broken(value: String)
      given NativeConverter[Broken]:
        extension (value: Broken) def toNative: js.Any = throw Rejected("private converter detail")
        def fromNative(state: ParseState): Broken = Broken("")
      var releases = 0
      val response = new Response
      response.invoke(KHandler.define(Scope.ensure(Sync.defer { releases += 1 }).map(_ => Broken("value")))).map { body =>
        assertEquals(releases, 1)
        assertEquals(response.status, 500)
        opaque(body)
      }
    }

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
        val response = new Response
        val running = response.invoke(KHandler.define {
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
          assertEquals(response.status, status)
          assertEquals(releases, 1)
          assert(!response.hasListener)
        TestDeadline(checked).andThen { case _ => release.trySuccess(()) }
      }.map(_ => ())
    }

    test("disconnect interrupts pending work and waits for asynchronous cleanup") {
      val acquired = Promise[Unit]()
      val finalizing = Promise[Unit]()
      val release = Promise[Unit]()
      var releases = 0
      val response = new Response
      val handler = KHandler.define[String] {
        Scope.acquireRelease(Sync.defer { acquired.success(()); () }) { _ =>
          Sync.defer { finalizing.success(()); () }
            .map(_ => Async.fromFuture(release.future))
            .map(_ => Sync.defer { releases += 1 })
        }.map(_ => Async.never[String])
      }
      val running = response.invoke(handler)
      val checked = for
        _ <- acquired.future
        _ = response.disconnect()
        _ <- finalizing.future
        _ = assert(!running.isCompleted, "the response must await cleanup after interruption")
        _ = release.success(())
        body <- running
      yield
        assert(js.isUndefined(body))
        assertEquals(releases, 1)
        assert(!response.hasListener)
        assert(response.event.handled, "h3 must not manufacture a 404 for a disconnected request")
        assertEquals(response.writes, 0)
        response.disconnect()
        assertEquals(releases, 1)
      TestDeadline(checked).andThen { case _ => release.trySuccess(()) }
    }

    test("an already closed response never starts the request program") {
      var started = false
      val response = new Response
      response.disconnect()
      response.invoke(KHandler.define(Sync.defer { started = true; Greeting("unexpected") })).map { body =>
        assert(js.isUndefined(body))
        assert(!started)
        assert(response.event.handled)
        assert(!response.hasListener)
      }
    }

    test("normal completion removes the disconnect listener and late close cannot repeat cleanup") {
      var releases = 0
      val response = new Response
      response.invoke(KHandler.define(Scope.ensure(Sync.defer { releases += 1 }).map(_ => Greeting("ok")))).map { _ =>
        assertEquals(releases, 1)
        assert(!response.hasListener)
        response.disconnect()
        assertEquals(releases, 1)
      }
    }
end HandlerBoundarySuite
