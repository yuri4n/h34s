package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.zioh3.ZioTesting.*
import org.getshaka.nativeconverter.{NativeConverter, ParseState}
import zio.*
import zio.test.*

import scala.scalajs.js

/** The ZIO handler boundary over the Kyo core (Z1-Z12, K3-K14 and C12 for ZIO). */
object HandlerBoundarySpec extends ZIOSpecDefault:
    final case class Greeting(message: String) derives NativeConverter
    final case class Rejected(message: String) extends SafeError:
        override def status: Int = 422

    private def status(call: Call): Int = call.response.status

    // claude 2026-10-19: created: one capture at a time, because tests in this spec run concurrently (issue #40 review)
    private val consoleLock = Unsafe.unsafely(Semaphore.unsafe.make(1))

    // claude 2026-10-19: created: captures console.error lines (the adapter's logging seam) while `program` runs; changed: under consoleLock (review)
    private def consoleErrors[R, E, A](program: ZIO[R, E, A]): ZIO[R, E, (A, List[String])] =
      consoleLock.withPermit(ZIO.acquireReleaseWith(ZIO.succeed {
        val console  = js.Dynamic.global.console
        val original = console.error
        val lines    = scala.collection.mutable.ListBuffer.empty[String]
        val capture: js.Function2[js.Any, js.Any, Unit] =
          (first, second) => lines += List(first, second).filterNot(js.isUndefined).map(String.valueOf).mkString(" ")
        console.error = capture
        (original, lines)
      })((original, _) => ZIO.succeed(js.Dynamic.global.console.error = original))((_, lines) =>
        program.map(result => (result, lines.toList)),
      ))

    def spec = suite("ZHandler.define")(
      // Z1, C2
      test("converts a model and passes the request snapshot to the handler function") {
        val call = Call()
        for body <- call.invoke(ZHandler.define(request => ZIO.succeed(Greeting(request.path))))
        yield assertTrue(status(call) == 200, wire(body) == "{\"data\":{\"message\":\"/test\"}}", !call.response.hasListener)
      },
      // K3
      test("a reusable handler runs once per request with its own snapshot") {
        var evaluations = 0
        for
          gate <- Promise.make[Nothing, Unit]
          handler = ZHandler.define { (request: Request) =>
            evaluations += 1
            gate.await.as(Greeting(request.path))
          }
          atDefinition = evaluations
          first <- Call("/first").invoke(handler).fork
          second <- Call("/second").invoke(handler).fork
          _ <- gate.succeed(())
          a <- first.join
          b <- second.join
        yield assertTrue(
          atDefinition == 0, evaluations == 2,
          data(a).message.asInstanceOf[String] == "/first", data(b).message.asInstanceOf[String] == "/second",
        )
      },
      // Z2
      test("failWith keeps the approved status and one entry per message") {
        val one = Call()
        val several = Call()
        for
          oneBody <- one.invoke(ZHandler.define[String](_ => ZHandler.failWith(409, "Already exists")))
          severalBody <- several.invoke(ZHandler.define[String](_ => ZHandler.failWith(400, "Title is required", "Text is required")))
        yield assertTrue(
          status(one) == 409, wire(oneBody) == errors("Already exists"),
          status(several) == 400, wire(severalBody) == errors("Title is required", "Text is required"),
        )
      },
      test("an ErrorResponse failure defaults to 400; a status outside 400-599 is a defect") {
        val plain = Call()
        val odd = Call()
        for
          plainBody <- plain.invoke(ZHandler.define[String](_ => ZIO.fail(ErrorResponse(prelude.NonEmptyList("Bad input")))))
          oddBody <- odd.invoke(ZHandler.define[String](_ => ZHandler.failWith(200, "looks fine")))
        yield assertTrue(status(plain) == 400, wire(plainBody) == errors("Bad input"), status(odd) == 500, wire(oddBody) == opaque)
      },
      // Z3
      test("a SafeError failure renders only its approved message and status") {
        val call = Call()
        for body <- call.invoke(ZHandler.define[String](_ => ZIO.fail(Rejected("Invalid name"))))
        yield assertTrue(status(call) == 422, wire(body) == errors("Invalid name"))
      },
      // Z4, K6
      test("typed exceptions, defects, thrown SafeErrors and construction exceptions are opaque 500s") {
        val handlers = List(
          ZHandler.define[String](_ => ZIO.fail(new Exception("private database details"))),
          ZHandler.define[String](_ => ZIO.die(new Exception("private defect details"))),
          ZHandler.define[String](_ => ZIO.succeed(throw Rejected("unapproved thrown message"))),
          ZHandler.define[String](_ => ZIO.die(Rejected("unapproved defect message"))),
          ZHandler.define[String](_ => throw new Exception("private construction failure")),
          ZHandler.define[String](_ => ZIO.interrupt),
        )
        ZIO.foreach(handlers) { handler =>
          val call = Call()
          call.invoke(handler).map(body => status(call) == 500 && wire(body) == opaque && !call.response.hasListener)
        }.map(results => assertTrue(results.forall(identity)))
      },
      test("several typed failures are a defect, a single failure with interrupted siblings stays approved") {
        val several = Call()
        val single = Call()
        for
          severalBody <- several.invoke(ZHandler.define[String](_ =>
            ZIO.failCause(Cause.fail(Rejected("first")) && Cause.fail(Rejected("second"))),
          ))
          singleBody <- single.invoke(ZHandler.define[String](_ =>
            ZIO.failCause(Cause.fail(Rejected("first")) && Cause.interrupt(FiberId.None)),
          ))
        yield assertTrue(status(several) == 500, wire(severalBody) == opaque, status(single) == 422, wire(singleBody) == errors("first"))
      },
      // Z5, D6
      test("development diagnostics contain the original failure, its nested cause and the Scala source file") {
        val call = Call(development = true)
        val failure = ZIO.fail(new Exception("database operation failed", new Exception("debugging detail")))
        for body <- call.invoke(ZHandler.define[String](_ => failure))
        yield
          val text = cause(body)
          assertTrue(
            status(call) == 500,
            body.asInstanceOf[js.Dynamic].errors.asInstanceOf[js.Array[js.Dynamic]](0).message.asInstanceOf[String] == "Internal server error",
            text.contains("database operation failed"), text.contains("debugging detail"), text.contains("HandlerBoundarySpec.scala"),
          )
      },
      // Z6
      test("development defects include diagnostics and still release request resources") {
        var released = false
        val call = Call(development = true)
        val handler = ZHandler.define[String](_ =>
          ZIO.acquireRelease(ZIO.unit)(_ => ZIO.succeed { released = true }) *> ZIO.die(new Exception("debug defect")),
        )
        for body <- call.invoke(handler)
        yield assertTrue(status(call) == 500, cause(body).contains("debug defect"), released)
      },
      // Z7, K8
      test("only boolean true in the server context enables diagnostics, without leaking across requests") {
        val handler = ZHandler.define[String](_ => ZIO.fail(new Exception("private detail")))
        for
          _ <- Call(development = true).invoke(handler)
          results <- ZIO.foreach(List[js.Any](false, "true", 1, js.undefined)) { mode =>
            val call = Call(development = mode, headers = Seq("x-h34s-development" -> "true"))
            call.invoke(handler).map(body => status(call) == 500 && wire(body) == opaque)
          }
          debug <- Call(development = false, debug = true).invoke(handler)
        yield assertTrue(results.forall(identity), cause(debug).contains("private detail"))
      },
      // Z8
      test("safe and explicit failures keep their status and body in development, without diagnostics") {
        val safe = Call(development = true, debug = true)
        val explicit = Call(development = true)
        for
          safeBody <- safe.invoke(ZHandler.define[String](_ => ZIO.fail(Rejected("Invalid name"))))
          explicitBody <- explicit.invoke(ZHandler.define[String](_ => ZHandler.failWith(409, "Already exists")))
        yield assertTrue(
          status(safe) == 422, wire(safeBody) == errors("Invalid name"),
          status(explicit) == 409, wire(explicitBody) == errors("Already exists"),
        )
      },
      // Z9
      // claude 2026-10-19: changed: a finalizer defect no longer wins; it is logged, also in development mode (issue #40)
      test("a finalizer defect keeps a safe failure and a success, in development and production") {
        def handler(outcome: IO[Rejected, String]) = ZHandler.define[String](_ =>
          ZIO.acquireRelease(ZIO.unit)(_ => ZIO.die(new Exception("cleanup defect"))) *> outcome,
        )
        val development = Call(development = true)
        val production = Call()
        val succeeded = Call()
        for
          developmentBody <- development.invoke(handler(ZIO.fail(Rejected("Invalid name"))))
          productionBody <- production.invoke(handler(ZIO.fail(Rejected("Invalid name"))))
          succeededBody <- succeeded.invoke(handler(ZIO.succeed("ok")))
        yield assertTrue(
          status(development) == 422, wire(developmentBody) == errors("Invalid name"),
          status(production) == 422, wire(productionBody) == errors("Invalid name"),
          status(succeeded) == 200, wire(succeededBody) == "{\"data\":\"ok\"}",
        )
      },
      // claude 2026-10-19: created: negative control for issue #40 (ZIO follows Kyo on finalizer failures); changed: a 204 case and the discarded header on the 422 (review)
      test("a dying request-scope finalizer is logged and keeps the computed response: value, Unit and safe failure") {
        def handler[B: ZResponseBody](outcome: ZIO[HandlerEnv, Throwable, B]) = ZHandler.define[B](_ =>
          ZIO.acquireRelease(ZIO.unit)(_ => ZIO.die(new Exception("logged cleanup defect"))) *>
            Response.status(Status.Created) *> Response.header("x-kept", "yes") *> outcome,
        )
        val value = Call()
        val empty = Call()
        val rejected = Call()
        val noContent = Call()
        for
          captured <- consoleErrors(
            for
              valueBody <- value.invoke(handler(ZIO.succeed(Greeting("ok"))))
              _ <- empty.invoke(handler(ZIO.unit))
              rejectedBody <- rejected.invoke(handler[String](ZIO.fail(Rejected("Invalid name"))))
              noContentBody <- noContent.invoke(ZHandler.define(_ =>
                ZIO.acquireRelease(ZIO.unit)(_ => ZIO.die(new Exception("logged cleanup defect"))).unit,
              ))
            yield (valueBody, rejectedBody, noContentBody),
          )
          ((valueBody, rejectedBody, noContentBody), logged) = captured
        yield assertTrue(
          status(value) == 201, wire(valueBody) == "{\"data\":{\"message\":\"ok\"}}",
          value.response.header("x-kept").contains("yes"),
          status(empty) == 201, empty.response.header("x-kept").contains("yes"),
          status(rejected) == 422, wire(rejectedBody) == errors("Invalid name"),
          rejected.response.header("x-kept").isEmpty,
          status(noContent) == 204, noContentBody == null,
          logged.count(line => line.contains("h34s: a ZIO finalizer failed") && line.contains("logged cleanup defect")) == 4,
        )
      },
      // claude 2026-10-19: created: unusual finalizer failures stay logged and keep the response (issue #40 review)
      test("a finalizer whose defect cannot be rendered, one that ends in interruption, and a streaming handler that fails before its stream") {
        final class Unrenderable extends Exception:
          override def getMessage: String = throw new IllegalStateException("no message")
        val interrupter = FiberId.Runtime(424242, 0L, Trace.empty)
        def created(finalizer: UIO[Any]) = ZHandler.define(_ =>
          ZIO.acquireRelease(ZIO.unit)(_ => finalizer) *> Response.status(Status.Created).as(Greeting("ok")),
        )
        val unrenderable = Call()
        val interrupted = Call()
        val streaming = Call()
        // claude 2026-10-20: changed: the stream is sent with ZHandler.sendStream after the failure (ADR-0004)
        val failsBeforeStream = ZHandler.define(_ =>
          ZIO.acquireRelease(ZIO.unit)(_ => ZIO.die(new Exception("stream handler cleanup defect"))) *>
            ZIO.fail(Rejected("Invalid name")) *> ZHandler.sendStream(zio.stream.ZStream.empty),
        )
        for
          captured <- consoleErrors(
            for
              _ <- unrenderable.invoke(created(ZIO.die(new Unrenderable)))
              _ <- interrupted.invoke(created(ZIO.interruptAs(interrupter)))
              streamingBody <- streaming.invoke(failsBeforeStream)
            yield streamingBody,
          )
          (streamingBody, logged) = captured
          finalizerLines = logged.filter(_.contains("h34s: a ZIO finalizer failed"))
        yield assertTrue(
          status(unrenderable) == 201, finalizerLines.count(_.contains("could not be rendered")) == 1,
          status(interrupted) == 201, finalizerLines.count(_.contains("424242")) == 1,
          status(streaming) == 422, wire(streamingBody) == errors("Invalid name"),
          finalizerLines.count(_.contains("stream handler cleanup defect")) == 1,
        )
      },
      // claude 2026-10-19: created: documents that the #40 rule covers the request scope only (Codex review on #49)
      test("a release that dies inside the handler's own scope follows the runtime's own rules") {
        val call = Call()
        val handler = ZHandler.define(_ =>
          ZIO.scoped(ZIO.addFinalizer(ZIO.die(new Exception("inner scope defect"))) *> ZIO.succeed("ok")),
        )
        for body <- call.invoke(handler)
        yield assertTrue(status(call) == 500, wire(body) == opaque)
      },
      // Z10, K9
      test("request resources close once on success, typed failure and defect") {
        var released = 0
        val resource = ZIO.acquireRelease(ZIO.unit)(_ => ZIO.succeed { released += 1 })
        for
          _ <- Call().invoke(ZHandler.define(_ => resource.as(Greeting("ok"))))
          afterSuccess = released
          _ <- Call().invoke(ZHandler.define[String](_ => resource *> ZIO.fail(new Exception("failure"))))
          _ <- Call().invoke(ZHandler.define[String](_ => resource *> ZIO.die(new Exception("defect"))))
        yield assertTrue(afterSuccess == 1, released == 3)
      },
      // Z11
      test("application layers are provided around the handler program with ordinary ZIO") {
        final case class Config(prefix: String)
        val program: Request => ZIO[Config, Nothing, Greeting] =
          request => ZIO.serviceWith[Config](config => Greeting(config.prefix + request.path))
        for body <- Call().invoke(ZHandler.define(request => program(request).provide(ZLayer.succeed(Config("hello")))))
        yield assertTrue(data(body).message.asInstanceOf[String] == "hello/test")
      },
      // Z12, K4
      test("Unit is a 204 without a body, without importing a converter") {
        val call = Call()
        for body <- call.invoke(ZHandler.define(_ => ZIO.unit))
        yield assertTrue(body == null, !js.isUndefined(body), status(call) == 204)
      },
      // K10, D4
      test("a throwing response converter is an opaque 500 after request cleanup") {
        final case class Broken(value: String)
        given NativeConverter[Broken]:
          extension (value: Broken) def toNative: js.Any = throw Rejected("private converter detail")
          def fromNative(state: ParseState): Broken = Broken("")
        var releases = 0
        val call = Call()
        val handler = ZHandler.define(_ => ZIO.acquireRelease(ZIO.unit)(_ => ZIO.succeed { releases += 1 }).as(Broken("value")))
        for body <- call.invoke(handler)
        yield assertTrue(releases == 1, status(call) == 500, wire(body) == opaque)
      },
      // K11, C6
      test("asynchronous ZIO finalizers finish before success, safe failure and defect settle") {
        val outcomes: List[(Int, IO[Throwable, String])] = List(
          200 -> ZIO.succeed("ok"),
          422 -> ZIO.fail(Rejected("Invalid name")),
          500 -> ZIO.die(new Exception("private defect")),
        )
        ZIO.foreach(outcomes) { (expected, outcome) =>
          for
            finalizing <- Promise.make[Nothing, Unit]
            release <- Promise.make[Nothing, Unit]
            released <- Ref.make(0)
            call = Call()
            handler = ZHandler.define[String](_ =>
              ZIO.acquireRelease(ZIO.unit)(_ => finalizing.succeed(()) *> release.await *> released.update(_ + 1)) *> outcome,
            )
            running <- call.invoke(handler).fork
            _ <- finalizing.await
            _ <- turns(3)
            pending <- running.poll
            _ <- release.succeed(())
            _ <- running.join
            count <- released.get
          yield pending.isEmpty && count == 1 && status(call) == expected && !call.response.hasListener
        }.map(results => assertTrue(results == List(true, true, true)))
      },
      // K12, C7, D1
      test("disconnect interrupts a non-stream handler and waits for its asynchronous ZIO finalizer") {
        for
          acquired <- Promise.make[Nothing, Unit]
          finalizing <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          released <- Ref.make(0)
          call = Call()
          handler = ZHandler.define[String](_ =>
            ZIO.acquireRelease(acquired.succeed(()))(_ => finalizing.succeed(()) *> release.await *> released.update(_ + 1)) *>
              ZIO.never,
          )
          running <- call.invoke(handler).fork
          _ <- acquired.await
          _ <- ZIO.succeed(call.response.disconnect())
          _ <- finalizing.await
          _ <- turns(3)
          pending <- running.poll
          _ <- release.succeed(())
          body <- running.join
          count <- released.get
          _ <- ZIO.succeed(call.response.disconnect())
          again <- released.get
        yield assertTrue(
          pending.isEmpty, js.isUndefined(body), count == 1, again == 1, !call.response.hasListener, call.handled,
          call.response.writes == 0, call.response.headerNames.isEmpty,
        )
      },
      // claude 2026-10-19: created: a dying finalizer during a disconnect is logged, and the core still writes nothing (issue #40 review)
      test("disconnect: a dying asynchronous finalizer is logged; the request settles after it and writes nothing") {
        for
          acquired <- Promise.make[Nothing, Unit]
          finalizing <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          call = Call()
          handler = ZHandler.define[String](_ =>
            ZIO.acquireRelease(acquired.succeed(()))(_ =>
              finalizing.succeed(()) *> release.await *> ZIO.die(new Exception("disconnect cleanup defect")),
            ) *> ZIO.never,
          )
          captured <- consoleErrors(
            for
              running <- call.invoke(handler).fork
              _ <- acquired.await
              _ <- ZIO.succeed(call.response.disconnect())
              _ <- finalizing.await
              _ <- turns(3)
              pending <- running.poll
              _ <- release.succeed(())
              body <- running.join
            yield (pending, body),
          )
          ((pending, body), logged) = captured
        yield assertTrue(
          pending.isEmpty, js.isUndefined(body), call.handled, call.response.writes == 0, call.response.headerNames.isEmpty,
          logged.count(line => line.contains("h34s: a ZIO finalizer failed") && line.contains("disconnect cleanup defect")) == 1,
        )
      },
      test("ZIO.uninterruptible: after a disconnect the step ends, then request finalizers run, then the request settles") {
        for
          events <- Ref.make(List.empty[String])
          started <- Promise.make[Nothing, Unit]
          call = Call()
          handler = ZHandler.define[String](_ =>
            ZIO.acquireRelease(ZIO.unit)(_ => events.update(_ :+ "connection released")) *>
              (started.succeed(()) *> ZIO.sleep(100.millis) *> events.update(_ :+ "step done")).uninterruptible.as("committed"),
          )
          running <- call.invoke(handler).fork
          _ <- started.await
          _ <- ZIO.succeed(call.response.disconnect())
          body <- running.join
          atSettle <- events.get
        yield assertTrue(
          atSettle == List("step done", "connection released"), js.isUndefined(body), call.response.writes == 0,
        )
      } @@ TestAspect.withLiveClock,
      // claude 2026-10-19: changed: the streamed scope hand-over is gone; the afterProgram seam now covers every body, before the fiber closes the scope
      test("an interrupt after the program returned still closes the request scope once") {
        import dev.yuri4n.exts.kyoh3.{KHandler, ResponseBody}
        import dev.yuri4n.exts.zioh3.internal.ZioRequest
        for
          released <- Ref.make(0)
          call = Call()
          runtime = Runtime.default
          handler = {
            given ResponseBody[String] = ZResponseBody.core(summon[ZResponseBody[String]], runtime)
            // The interrupt arrives inside the fiber's uninterruptible region, after the program
            // returned and before the fiber closes the scope: it replaces the fiber's Exit.
            val window = ZIO.succeed(call.response.disconnect()) *> ZIO.sleep(50.millis)
            KHandler.define[String](HandlerOptions.default)(_ =>
              ZioRequest.run(runtime, afterProgram = window)(
                ZIO.acquireRelease(ZIO.unit)(_ => released.update(_ + 1)).as("never sent"),
              ),
            )
          }
          body <- call.invoke(handler)
          count <- released.get
        yield assertTrue(count == 1, js.isUndefined(body), call.response.chunks.isEmpty, call.response.writes == 0)
      },
      // K13
      test("an already closed response never starts the request program") {
        var started = false
        val call = Call()
        call.response.disconnect()
        for body <- call.invoke(ZHandler.define(_ => ZIO.succeed { started = true; Greeting("unexpected") }))
        yield assertTrue(js.isUndefined(body), !started, call.handled, !call.response.hasListener)
      },
      // K14
      test("normal completion removes the disconnect listener and a late close cannot repeat cleanup") {
        var releases = 0
        val call = Call()
        val handler = ZHandler.define(_ => ZIO.acquireRelease(ZIO.unit)(_ => ZIO.succeed { releases += 1 }).as(Greeting("ok")))
        for
          _ <- call.invoke(handler)
          afterCompletion = releases
          _ <- ZIO.succeed(call.response.disconnect())
        yield assertTrue(afterCompletion == 1, releases == 1, !call.response.hasListener)
      },
      test("handlers run on the given ZIO Runtime") {
        for
          runtime <- ZIO.logAnnotate("tenant", "acme")(ZIO.runtime[Any])
          call = Call()
          body <- call.invoke(ZHandler.define(_ => ZIO.logAnnotations.map(_.getOrElse("tenant", "none")))(using summon, runtime))
          default <- Call().invoke(ZHandler.define(_ => ZIO.logAnnotations.map(_.getOrElse("tenant", "none"))))
        yield assertTrue(data(body).asInstanceOf[String] == "acme", data(default).asInstanceOf[String] == "none")
      },
    ) @@ TestAspect.timeout(45.seconds)
end HandlerBoundarySpec
