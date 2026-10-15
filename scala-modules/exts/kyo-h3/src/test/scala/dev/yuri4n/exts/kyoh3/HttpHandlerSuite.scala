package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.h3.CommonEvHandler
import kyo.*
import munit.FunSuite
import org.getshaka.nativeconverter.NativeConverter

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.util.Try

@js.native
@JSImport("node:http", JSImport.Namespace)
private object NodeHttp extends js.Object:
    def createServer(listener: js.Function2[js.Dynamic, js.Dynamic, Unit]): js.Dynamic = js.native
    def request(options: js.Any, listener: js.Function1[js.Dynamic, Unit]): js.Dynamic = js.native

@js.native
@JSImport("h3", JSImport.Namespace)
private object NativeH3 extends js.Object:
    def createApp(options: js.Any): js.Dynamic = js.native
    def toNodeListener(app: js.Any): js.Function2[js.Dynamic, js.Dynamic, Unit] = js.native

/** Loopback HTTP tests cover the h3 app boundary, not only direct handler invocation. */
class HttpHandlerSuite extends FunSuite:
    given ExecutionContext = ExecutionContext.global
    override val munitTimeout: FiniteDuration = 60.seconds

    final case class Input(name: String) derives NativeConverter
    final case class Greeting(message: String) derives NativeConverter

    private final class Server(handler: CommonEvHandler):
        var transportErrors = 0
        val completed = Promise[Unit]()
        private val onError: js.Function2[js.Any, js.Any, Unit] = (_, _) => { transportErrors += 1 }
        private val onAfterResponse: js.Function2[js.Any, js.Any, Unit] = (_, _) => { completed.trySuccess(()); () }
        private val app = NativeH3.createApp(js.Dynamic.literal(onError = onError, onAfterResponse = onAfterResponse))
        app.use(handler.asInstanceOf[js.Any])
        private val native = NodeHttp.createServer(NativeH3.toNodeListener(app))
        private val listening = Promise[Int]()
        native.once("error", ((error: js.Any) => { listening.tryFailure(js.JavaScriptException(error)); () }): js.Function1[js.Any, Unit])
        native.listen(0, "127.0.0.1", (() => { listening.success(native.address().port.asInstanceOf[Int]); () }): js.Function0[Unit])
        val port: Future[Int] = listening.future
        def close(): Future[Unit] =
          val closed = Promise[Unit]()
          native.close((() => { closed.trySuccess(()); () }): js.Function0[Unit])
          native.closeAllConnections()
          closed.future

    private def withServer[A](handler: CommonEvHandler)(f: (Server, Int) => Future[A]): Future[A] =
      val server = new Server(handler)
      TestDeadline(server.port.flatMap(port => Future.fromTry(Try(f(server, port))).flatten))
        .transformWith(result => server.close().flatMap(_ => Future.fromTry(result)))

    test("h3 reads a JSON body and serializes the native Kyo result over HTTP") {
      val handler = KHandler.define(KHandler.readBody[Input].map(input => Greeting(s"Hello ${input.name}")))
      withServer(handler) { (server, port) =>
        val result = Promise[(Int, String)]()
        val onResponse: js.Function1[js.Dynamic, Unit] = response =>
          var body = ""
          response.setEncoding("utf8")
          response.on("data", ((chunk: String) => body += chunk): js.Function1[String, Unit])
          response.on("end", (() => {
            result.success((response.statusCode.asInstanceOf[Int], body))
            ()
          }): js.Function0[Unit])
          ()
        val request = NodeHttp.request(js.Dynamic.literal(
          hostname = "127.0.0.1", port = port, method = "POST", path = "/greeting",
          headers = js.Dynamic.literal("content-type" -> "application/json"),
        ), onResponse)
        request.on("error", ((error: js.Any) => { result.tryFailure(js.JavaScriptException(error)); () }): js.Function1[js.Any, Unit])
        request.end("{\"name\":\"Scala\"}")
        result.future.map { case (status, body) =>
          assertEquals(status, 200)
          assertEquals(body, "{\"message\":\"Hello Scala\"}")
          assertEquals(server.transportErrors, 0)
        }
      }
    }

    test("an actual client abort waits for request cleanup without an h3 error response") {
      val acquired = Promise[Unit]()
      val finalizing = Promise[Unit]()
      val release = Promise[Unit]()
      var releases = 0
      val handler = KHandler.define[String] {
        Scope.acquireRelease(Sync.defer { acquired.success(()); () }) { _ =>
          Sync.defer { finalizing.success(()); () }
            .map(_ => Async.fromFuture(release.future))
            .map(_ => Sync.defer { releases += 1 })
        }.map(_ => Async.never[String])
      }
      withServer(handler) { (server, port) =>
        val unexpectedlyResponded = Promise[Unit]()
        val onResponse: js.Function1[js.Dynamic, Unit] = _ => { unexpectedlyResponded.trySuccess(()); () }
        val request = NodeHttp.request(js.Dynamic.literal(hostname = "127.0.0.1", port = port, path = "/pending"), onResponse)
        // ECONNRESET is expected when this test destroys its own client request.
        request.on("error", ((_: js.Any) => ()): js.Function1[js.Any, Unit])
        request.end()
        val checked = for
          _ <- acquired.future
          _ = request.destroy()
          _ <- finalizing.future
          _ = assertEquals(releases, 0)
          _ = assert(!server.completed.isCompleted, "h3 must await cleanup")
          _ = release.success(())
          _ <- server.completed.future
        yield
          assertEquals(releases, 1)
          assertEquals(server.transportErrors, 0)
          assert(!unexpectedlyResponded.isCompleted)
        TestDeadline(checked).andThen { case _ => release.trySuccess(()) }
      }
    }
end HttpHandlerSuite
