package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.h3.CommonEvHandler
import org.getshaka.nativeconverter.NativeConverter
import typings.h3.mod.{EventHandlerRequest, H3Event}
import zio.*
import zio.test.*

import scala.scalajs.js

object HandlerBoundarySpec extends ZIOSpecDefault:
    final case class Greeting(message: String) derives NativeConverter
    final case class Rejected(message: String) extends SafeError:
        override def status = 422

    private def invoke(handler: CommonEvHandler): Task[(Int, js.Any)] =
      val response = js.Dynamic.literal(statusCode = 200, statusMessage = "OK", headersSent = false, writableEnded = false)
      val request = js.Dynamic.literal(method = "GET", url = "/test", headers = js.Dynamic.literal())
      val event = new H3Event[EventHandlerRequest](request, response)
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
