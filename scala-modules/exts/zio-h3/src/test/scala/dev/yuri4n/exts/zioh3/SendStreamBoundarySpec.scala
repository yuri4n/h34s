package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.h3.CommonEvHandler
import dev.yuri4n.exts.ntcvzio.given
import org.getshaka.nativeconverter.NativeConverter.given
import zio.*
import zio.stream.ZStream
import zio.test.*

import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Tests `defineEventHandler` and `SH3Event.sendStream` through h3 and a Node HTTP server.
  *
  * Created by Claude Code on 2026-10-13. The converter tests in `StreamConversionSpec` stop at a
  * `WritableStream`. These tests mount the handler in an h3 app, serve it on a loopback port with
  * `node:http`, send requests with Node's global `fetch`, and check what the client receives. This
  * is the same path Nitro uses. It needs h3 at run time, so the zio-h3 test build links CommonJS
  * modules; see `build.sbt`.
  */
object SendStreamBoundarySpec extends ZIOSpecDefault:

    given ExecutionContext = ExecutionContext.global

    @js.native
    @JSImport("h3", JSImport.Namespace)
    private object H3 extends js.Object

    @js.native
    @JSImport("node:http", JSImport.Namespace)
    private object NodeHttp extends js.Object

    def spec = suite("SH3Event.sendStream through h3 and node:http")(
      test("a finite stream arrives as one 200 response") {
        val handler = defineEventHandler(ZHandler.sendStream(ZStream("a", "b", "c")))
        for
            url      <- serve(handler)
            response <- fetch(url)
            body     <- text(response)
        yield assertTrue(status(response) == 200, body == "abc")
      },
      test("a failure before the first chunk becomes a 500 error response") {
        val failing: TaskStream[String] = ZStream.fail(new Exception("boom"))
        val handler                     = defineEventHandler(ZHandler.sendStream(failing))
        for
            url      <- serve(handler)
            response <- fetch(url)
            body     <- text(response)
        yield assertTrue(status(response) == 500, body == """{"errors":["boom"]}""")
      },
      test("a failure after the first chunk ends the response with a transport error") {
        // The pause lets h3 write "a" before the source fails, so the headers are already sent.
        val failing: TaskStream[String] =
          ZStream("a") ++ ZStream.fromZIO(ZIO.fail(new Exception("late")).delay(50.millis))
        val handler = defineEventHandler(ZHandler.sendStream(failing))
        for
            url      <- serve(handler)
            response <- fetch(url)
            body     <- text(response).either
        yield assertTrue(status(response) == 200, body.isLeft)
      },
      test("an ErrorResponse from the handler keeps its status and messages") {
        val handler = defineEventHandler[Unit](ZHandler.failWith(404, "missing"))
        for
            url      <- serve(handler)
            response <- fetch(url)
            body     <- text(response)
        yield assertTrue(status(response) == 404, body == """{"errors":["missing"]}""")
      },
      test("a client disconnect interrupts the source stream") {
        for
            ended       <- Promise.make[Nothing, Unit]
            source       = ZStream
                             .repeatZIOWithSchedule(ZIO.succeed("x"), Schedule.spaced(10.millis))
                             .ensuring(ended.succeed(()))
            handler      = defineEventHandler(ZHandler.sendStream(source))
            url         <- serve(handler)
            abort        = js.Dynamic.newInstance(js.Dynamic.global.AbortController)()
            response    <- fetch(url, js.Dynamic.literal(signal = abort.signal))
            reader       = response.body.getReader()
            first       <- ZIO.fromPromiseJS(reader.read().asInstanceOf[js.Promise[js.Dynamic]])
            _            = abort.abort()
            interrupted <- ended.await.timeout(5.seconds)
        yield assertTrue(
          status(response) == 200,
          !first.done.asInstanceOf[Boolean],
          interrupted.isDefined,
        )
      },
    ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(30.seconds)

    /** Serves `handler` at every path of an h3 app on a loopback port and returns the base URL. The
      * server closes with the test's scope.
      */
    private def serve(handler: CommonEvHandler): ZIO[Scope, Throwable, String] =
      ZIO.acquireRelease(start(handler))(stop).map { server =>
          val port = server.address().port.asInstanceOf[Int]
          s"http://127.0.0.1:$port/stream"
      }

    private def start(handler: CommonEvHandler): Task[js.Dynamic] =
      ZIO.async { callback =>
          val h3  = H3.asInstanceOf[js.Dynamic]
          val app = h3.createApp()
          app.use(handler.asInstanceOf[js.Any])
          val server = NodeHttp.asInstanceOf[js.Dynamic].createServer(h3.toNodeListener(app))
          server.once(
            "error",
            js.Any.fromFunction1((error: js.Any) => callback(ZIO.fail(js.JavaScriptException(error)))),
          )
          server.listen(0, "127.0.0.1", js.Any.fromFunction0(() => callback(ZIO.succeed(server))))
          ()
      }

    /** Stops the server without waiting for idle keep-alive connections from `fetch`. */
    private def stop(server: js.Dynamic): UIO[Unit] =
      ZIO.async { callback =>
          server.closeAllConnections()
          server.close(js.Any.fromFunction1((_: js.Any) => callback(ZIO.unit)))
          ()
      }

    private def fetch(url: String, init: js.Any = js.undefined): Task[js.Dynamic] =
      ZIO.fromPromiseJS(js.Dynamic.global.fetch(url, init).asInstanceOf[js.Promise[js.Dynamic]])

    private def text(response: js.Dynamic): Task[String] =
      ZIO.fromPromiseJS(response.text().asInstanceOf[js.Promise[String]])

    private def status(response: js.Dynamic): Int = response.status.asInstanceOf[Int]
end SendStreamBoundarySpec
