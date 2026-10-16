package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.h3.CommonEvHandler
import org.getshaka.nativeconverter.NativeConverter
import dev.yuri4n.h34s.facades.h3.mod.{EventHandlerRequest, H3Event}
import zio.*
import zio.test.*

import scala.scalajs.js

object HandlerBoundarySpec extends ZIOSpecDefault:
    final case class Greeting(message: String) derives NativeConverter
    final case class Rejected(message: String) extends SafeError:
        override def status = 422

    private def invoke(handler: CommonEvHandler, development: js.Any = js.undefined): Task[(Int, js.Any)] =
      val response = js.Dynamic.literal(statusCode = 200, statusMessage = "OK", headersSent = false, writableEnded = false)
      val request = js.Dynamic.literal(method = "GET", url = "/test", headers = js.Dynamic.literal())
      val event = new H3Event[EventHandlerRequest](request, response)
      if !js.isUndefined(development) then
        event.context.asInstanceOf[js.Dynamic].updateDynamic("h34s")(js.Dynamic.literal(development = development))
      ZIO.fromPromiseJS(handler(event).asInstanceOf[js.Promise[js.Any]])
        .map(body => (response.statusCode.asInstanceOf[Int], body))

    def spec = suite("ZHandler.define")(
      test("converts a model and supplies request context without runtime plumbing") {
        for result <- invoke(ZHandler.define(for event <- ZHandler.getEvent yield Greeting(event.path)))
        yield assertTrue(result._1 == 200, result._2.asInstanceOf[js.Dynamic].message.asInstanceOf[String] == "/test")
      },
      test("preserves an explicitly safe response and its status") {
        for result <- invoke(ZHandler.define[String](ZHandler.failWith(409, "Already exists")))
        yield assertTrue(result._1 == 409, js.JSON.stringify(result._2) == "{\"errors\":[\"Already exists\"]}")
      },
      test("renders only the approved safe error message") {
        for result <- invoke(ZHandler.define[String](ZIO.fail(Rejected("Invalid name"))))
        yield assertTrue(result._1 == 422, js.JSON.stringify(result._2) == "{\"errors\":[\"Invalid name\"]}")
      },
      test("hides exception and defect details with status 500") {
        for
          failure <- invoke(ZHandler.define[String](ZIO.fail(new Exception("private database details"))))
          defect <- invoke(ZHandler.define[String](ZIO.die(new Exception("private defect details"))))
        yield assertTrue(
          failure._1 == 500, defect._1 == 500,
          js.JSON.stringify(failure._2) == "{\"errors\":[\"Internal server error\"]}",
          js.JSON.stringify(defect._2) == js.JSON.stringify(failure._2),
        )
      },
      test("includes the original failure, nested cause, and Scala trace in development") {
        val failure = ZIO.fail(new Exception("database operation failed", new Exception("debugging detail")))
        for result <- invoke(ZHandler.define[String](failure), true)
        yield
          val body = result._2.asInstanceOf[js.Dynamic]
          val cause = body.debug.cause.asInstanceOf[String]
          assertTrue(result._1 == 500, cause.contains("database operation failed"), cause.contains("debugging detail"), cause.contains("HandlerBoundarySpec.scala"))
      },
      test("includes defects in development while preserving resource cleanup") {
        for
          released <- Ref.make(false)
          handler = ZHandler.define[String](ZIO.acquireRelease(ZIO.unit)(_ => released.set(true)) *> ZIO.die(new Exception("debug defect")))
          result <- invoke(handler, true)
          closed <- released.get
        yield assertTrue(result._1 == 500, result._2.asInstanceOf[js.Dynamic].debug.cause.asInstanceOf[String].contains("debug defect"), closed)
      },
      test("requires boolean true from the server context and does not retain development state") {
        val handler = ZHandler.define[String](ZIO.fail(new Exception("private detail")))
        for
          _ <- invoke(handler, true)
          production <- invoke(handler, false)
          malformed <- invoke(handler, "true")
          missing <- invoke(handler)
        yield assertTrue(List(production, malformed, missing).forall { result =>
          result._1 == 500 && js.JSON.stringify(result._2) == "{\"errors\":[\"Internal server error\"]}"
        })
      },
      test("preserves safe response contracts in development") {
        for
          safe <- invoke(ZHandler.define[String](ZIO.fail(Rejected("Invalid name"))), true)
          explicit <- invoke(ZHandler.define[String](ZHandler.failWith(409, "Already exists")), true)
        yield assertTrue(
          safe._1 == 422, js.JSON.stringify(safe._2) == "{\"errors\":[\"Invalid name\"]}",
          explicit._1 == 409, js.JSON.stringify(explicit._2) == "{\"errors\":[\"Already exists\"]}",
        )
      },
      test("reports a finalizer defect even when the request fails with a safe error") {
        val handler = ZHandler.define[String](
          ZIO.acquireRelease(ZIO.unit)(_ => ZIO.die(new Exception("cleanup defect"))) *> ZIO.fail(Rejected("Invalid name")),
        )
        for
          development <- invoke(handler, true)
          production <- invoke(handler, false)
        yield assertTrue(
          development._1 == 500,
          development._2.asInstanceOf[js.Dynamic].debug.cause.asInstanceOf[String].contains("cleanup defect"),
          production._1 == 500,
          js.JSON.stringify(production._2) == "{\"errors\":[\"Internal server error\"]}",
        )
      },
      test("closes request resources on success and failure") {
        for
          released <- Ref.make(0)
          resource = ZIO.acquireRelease(ZIO.unit)(_ => released.update(_ + 1))
          _ <- invoke(ZHandler.define(resource *> ZIO.unit))
          _ <- invoke(ZHandler.define[String](resource *> ZIO.fail(new Exception("failure"))))
          count <- released.get
        yield assertTrue(count == 2)
      },
      test("accepts application layers resolved before the handler boundary") {
        final case class Config(prefix: String)
        val program = for config <- ZIO.service[Config] yield Greeting(config.prefix + " world")
        for result <- invoke(ZHandler.define(program.provide(ZLayer.succeed(Config("hello")))))
        yield assertTrue(result._2.asInstanceOf[js.Dynamic].message.asInstanceOf[String] == "hello world")
      },
      test("returns JavaScript undefined for Unit without a caller converter") {
        for result <- invoke(ZHandler.define(ZIO.unit))
        yield assertTrue(js.isUndefined(result._2))
      },
    )
end HandlerBoundarySpec
