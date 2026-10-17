package dev.yuri4n.exts.kyoh3

import kyo.*
import munit.FunSuite
import org.getshaka.nativeconverter.{NativeConverter, ParseState}

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

    /** `development` mimics the h34s Nitro plugin's per-request record. */
    private final class Server(handler: EventHandler, development: Boolean = false):
        var transportErrors = 0
        val completed = Promise[Unit]()
        private val onError: js.Function2[js.Any, js.Any, Unit] = (_, _) => { transportErrors += 1 }
        private val onAfterResponse: js.Function2[js.Any, js.Any, Unit] = (_, _) => { completed.trySuccess(()); () }
        private val onRequest: js.Function1[js.Dynamic, Unit] = event =>
          event.context.updateDynamic("h34s")(js.Dynamic.literal(version = 1, development = development))
        private val app = NativeH3.createApp(js.Dynamic.literal(onError = onError, onAfterResponse = onAfterResponse, onRequest = onRequest))
        app.use(handler.toNative)
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

    private def withServer[A](handler: EventHandler, development: Boolean = false)(f: (Server, Int) => Future[A]): Future[A] =
      val server = new Server(handler, development)
      TestDeadline(server.port.flatMap(port => Future.fromTry(Try(f(server, port))).flatten))
        .transformWith(result => server.close().flatMap(_ => Future.fromTry(result)))

    /** Sends `chunks` as the body. Without a Content-Length, Node uses chunked encoding. */
    private def post(
        port: Int,
        chunks: List[String],
        headers: Map[String, String] = Map("content-type" -> "application/json"),
    ): Future[(Int, String)] =
      val result = Promise[(Int, String)]()
      val onResponse: js.Function1[js.Dynamic, Unit] = response =>
        var content = ""
        response.setEncoding("utf8")
        response.on("data", ((chunk: String) => content += chunk): js.Function1[String, Unit])
        response.on("end", (() => {
          result.trySuccess((response.statusCode.asInstanceOf[Int], content))
          ()
        }): js.Function0[Unit])
        ()
      val request = NodeHttp.request(js.Dynamic.literal(
        hostname = "127.0.0.1", port = port, method = "POST", path = "/greeting",
        headers = js.Dictionary(headers.toSeq*),
      ), onResponse)
      // A server may answer 413 and close before the client finishes writing.
      request.on("error", ((error: js.Any) => { result.tryFailure(js.JavaScriptException(error)); () }): js.Function1[js.Any, Unit])
      chunks.foreach(chunk => request.write(chunk))
      request.end()
      result.future

    private def post(port: Int, body: String): Future[(Int, String)] =
      post(port, List(body), Map("content-type" -> "application/json", "content-length" -> body.getBytes("UTF-8").length.toString))

    // K15
    test("reads a JSON body and serializes the native Kyo result over HTTP") {
      val handler = KHandler.define(request => request.body.json[Input].map(input => Greeting(s"Hello ${input.name}")))
      withServer(handler) { (server, port) =>
        post(port, "{\"name\":\"Scala\"}").map { case (status, body) =>
          assertEquals(status, 200)
          assertEquals(body, "{\"message\":\"Hello Scala\"}")
          assertEquals(server.transportErrors, 0)
        }
      }
    }

    // K16
    test("a SafeError thrown by request decoding stays opaque and keeps development diagnostics") {
      final case class BrokenInput(value: String)
      final case class DecoderDefect(message: String) extends SafeError:
        override def status: Int = 422
      given NativeConverter[BrokenInput]:
        extension (value: BrokenInput) def toNative: js.Any = js.Dynamic.literal(value = value.value)
        def fromNative(state: ParseState): BrokenInput = throw DecoderDefect("private decoder detail")

      Future.traverse(List(false, true)) { development =>
        var releases = 0
        val handler = KHandler.define { request =>
          Scope.ensure(Sync.defer { releases += 1 }).andThen(request.body.json[BrokenInput]).map(_.value)
        }
        withServer(handler, development) { (server, port) =>
          post(port, "{\"value\":\"ignored\"}").map { case (status, body) =>
            assertEquals(status, 500)
            val response = js.JSON.parse(body)
            assertEquals(js.JSON.stringify(response.errors), "[\"Internal server error\"]")
            if development then assert(response.debug.cause.asInstanceOf[String].contains("private decoder detail"))
            else assertEquals(body, "{\"errors\":[\"Internal server error\"]}")
            assertEquals(releases, 1)
            assertEquals(server.transportErrors, 0)
          }
        }
      }.map(_ => ())
    }

    // K17, C8
    test("invalid JSON is a typed 400 that the handler can recover") {
      val recovering = KHandler.define { request =>
        Abort.run[HttpError | InvalidInput](request.body.json[Input]).map(_.fold(
          input => Greeting(input.name),
          error => Greeting(s"recovered: ${error.getMessage}"),
          error => throw error,
        ))
      }
      val rendering = KHandler.define(request => request.body.json[Input].map(_.name))
      for
        recovered <- withServer(recovering)((_, port) => post(port, "{invalid json}"))
        rendered <- withServer(rendering)((_, port) => post(port, "{invalid json}"))
      yield
        assertEquals(recovered, (200, "{\"message\":\"recovered: Request body is not valid JSON\"}"))
        assertEquals(rendered, (400, "{\"errors\":[\"Request body is not valid JSON\"]}"))
    }

    test("bodies above the limit fail with 413, by Content-Length and while streaming") {
      var started = 0
      val handler = KHandler.define { request =>
        Sync.defer(started += 1).andThen(request.body.json[Input](BodyLimit(16))).map(_.name)
      }
      withServer(handler) { (server, port) =>
        for
          declared <- post(port, "{\"name\":\"" + ("x" * 32) + "\"}")
          streamed <- post(port, List("{\"name\":\"", "x" * 10, "y" * 10, "\"}"))
          fits <- post(port, "{\"name\":\"ok\"}")
        yield
          assertEquals(declared, (413, "{\"errors\":[\"Request body is too large\"]}"))
          assertEquals(streamed, (413, "{\"errors\":[\"Request body is too large\"]}"))
          assertEquals(fits, (200, "ok"))
          assertEquals(started, 3)
          assertEquals(server.transportErrors, 0)
      }
    }

    test("typed JSON reads reject other media types with 415") {
      val handler = KHandler.define(request => request.body.json[Input].map(_.name))
      withServer(handler) { (_, port) =>
        val form = "name=Scala"
        post(port, List(form), Map("content-type" -> "application/x-www-form-urlencoded", "content-length" -> form.length.toString))
          .map(result => assertEquals(result, (415, "{\"errors\":[\"Request body must be JSON\"]}")))
      }
    }

    test("shape failures are a generic 422; other decoder exceptions are opaque 500s") {
      final case class Strict(value: String)
      given NativeConverter[Strict]:
        extension (value: Strict) def toNative: js.Any = value.value
        def fromNative(state: ParseState): Strict = throw RuntimeException("private decoder detail")
      final case class Counted(count: Int) derives NativeConverter
      for
        shape <- withServer(KHandler.define(request => request.body.json[Counted].map(_.count)))((_, port) =>
          post(port, "{\"count\":\"many\"}"),
        )
        defect <- withServer(KHandler.define(request => request.body.json[Strict].map(_.value)))((_, port) =>
          post(port, "{\"value\":\"x\"}"),
        )
      yield
        assertEquals(shape, (422, "{\"errors\":[\"Request body does not match the expected shape\"]}"))
        assertEquals(defect, (500, "{\"errors\":[\"Internal server error\"]}"))
    }

    test("the body is one-shot but reading it twice returns the same text") {
      val handler = KHandler.define { request =>
        for
          input <- request.body.json[Input]
          text <- request.body.text()
        yield Greeting(s"${input.name}|$text")
      }
      withServer(handler)((_, port) => post(port, "{\"name\":\"A\"}")).map { result =>
        assertEquals(result, (200, "{\"message\":\"A|{\\\"name\\\":\\\"A\\\"}\"}"))
      }
    }

    // K18, C7
    test("an actual client abort waits for request cleanup without an h3 error response") {
      val acquired = Promise[Unit]()
      val finalizing = Promise[Unit]()
      val release = Promise[Unit]()
      var releases = 0
      val handler = KHandler.define[String] { _ =>
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
