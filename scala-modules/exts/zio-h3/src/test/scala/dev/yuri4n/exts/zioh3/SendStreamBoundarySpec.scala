package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.h3.CommonEvHandler
import org.getshaka.nativeconverter.NativeConverter.given
import org.scalajs.dom
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
        // Changed by Claude Code on 2026-10-13: no type argument; an always-failing handler infers
        // `Responded`.
        val handler = defineEventHandler(ZHandler.failWith(404, "missing"))
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
      // Created by Claude Code on 2026-10-13: the three tests below pin the handler result rule
      // through a real h3 server: a no-content ending, a body ending, and a body returned after the
      // stream was sent, which `deliver` drops.
      test("sendNoContent answers 204 with an empty body") {
        val handler = defineEventHandler(ZHandler.sendNoContent)
        for
            url      <- serve(handler)
            response <- fetch(url)
            body     <- text(response)
        yield assertTrue(status(response) == 204, body == "")
      },
      test("a body value answers 200 with that body") {
        val handler = defineEventHandler(ZIO.succeed("hello"))
        for
            url      <- serve(handler)
            response <- fetch(url)
            body     <- text(response)
        yield assertTrue(status(response) == 200, body == "hello")
      },
      test("respondWith sends a Web Response, and a late failure of its streamed body ends the response") {
        // The body streams one chunk and then fails. The response must end with a transport error,
        // like a failing `sendStream`, instead of staying open. The failure must come after h3 wrote
        // the chunk, or it is an ordinary failure before the headers and the test checks the wrong
        // path. A timer is not reliable under load, so the stream fails from `pull`: the Web Streams
        // runtime calls `pull` only after the consumer took the first chunk, and the next macrotask
        // runs after h3's `write` for it, so the headers are sent by then.
        def failingBody(): js.Any =
          js.Dynamic.newInstance(js.Dynamic.global.ReadableStream)(
            js.Dynamic.literal(
              start = js.Any.fromFunction1 { (controller: js.Dynamic) =>
                  controller.enqueue(js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)().encode("a"))
                  ()
              },
              pull = js.Any.fromFunction1 { (controller: js.Dynamic) =>
                  js.Dynamic.global.setTimeout(
                    js.Any.fromFunction0(() => controller.error(js.Error("late body"))),
                    0,
                  )
                  ()
              },
            ),
          )
        val handler = defineEventHandler(
          ZHandler.respondWith(
            js.Dynamic.newInstance(js.Dynamic.global.Response)(failingBody()).asInstanceOf[dom.Response],
          ),
        )
        for
            url      <- serve(handler)
            response <- fetch(url)
            body     <- text(response).either
        yield assertTrue(status(response) == 200, body.isLeft)
      },
      test("a body returned after sendStream is dropped and the streamed response stands") {
        val handler = defineEventHandler(ZHandler.sendStream(ZStream("a", "b", "c")).as("late"))
        for
            url      <- serve(handler)
            response <- fetch(url)
            body     <- text(response)
        yield assertTrue(status(response) == 200, body == "abc")
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
