package dev.yuri4n.exts.kyoh3

import kyo.*
import munit.FunSuite
import org.getshaka.nativeconverter.{NativeConverter, ParseState}

import scala.collection.mutable
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}
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
    def createRouter(options: js.Any): js.Dynamic = js.native
    def toNodeListener(app: js.Any): js.Function2[js.Dynamic, js.Dynamic, Unit] = js.native

/** Loopback HTTP tests cover the h3 app boundary, not only direct handler invocation. */
class HttpHandlerSuite extends FunSuite:
    given ExecutionContext = ExecutionContext.global
    override val munitTimeout: FiniteDuration = 60.seconds

    final case class Input(name: String) derives NativeConverter
    final case class Greeting(message: String) derives NativeConverter

    /** `development` mimics the h34s Nitro plugin's per-request record. `routed` mounts the handler
      * in a preemptive h3 router at `/greeting`, as Nitro does; otherwise it is plain `app.use`.
      */
    private final class Server(handler: EventHandler, development: Boolean = false, routed: Boolean = false):
        var transportErrors = 0
        val completed = Promise[Unit]()
        private val onError: js.Function2[js.Any, js.Any, Unit] = (_, _) => { transportErrors += 1 }
        private val onAfterResponse: js.Function2[js.Any, js.Any, Unit] = (_, _) => { completed.trySuccess(()); () }
        private val onRequest: js.Function1[js.Dynamic, Unit] = event =>
          event.context.updateDynamic("h34s")(js.Dynamic.literal(version = 1, development = development))
        private val app = NativeH3.createApp(js.Dynamic.literal(onError = onError, onAfterResponse = onAfterResponse, onRequest = onRequest))
        if routed then
            val router = NativeH3.createRouter(js.Dynamic.literal(preemptive = true))
            router.use("/greeting", handler.toNative)
            app.use(router)
        else app.use(handler.toNative)
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

    private def withServer[A](handler: EventHandler, development: Boolean = false, routed: Boolean = false)(
        f: (Server, Int) => Future[A],
    ): Future[A] =
      val server = new Server(handler, development, routed)
      TestDeadline(server.port.flatMap(port => Future.fromTry(Try(f(server, port))).flatten))
        .transformWith(result => server.close().flatMap(_ => Future.fromTry(result)))

    /** Sends `chunks` as the body. Without a Content-Length, Node uses chunked encoding. */
    private def post(
        port: Int,
        chunks: List[String],
        headers: Map[String, String] = Map("content-type" -> "application/json"),
    ): Future[(Int, String)] =
      exchange(port, chunks, headers).map((status, _, content) => (status, content))

    /** Status, response headers and body text of one POST to `/greeting`. */
    private def exchange(
        port: Int,
        chunks: List[String],
        headers: Map[String, String],
    ): Future[(Int, js.Dictionary[js.Any], String)] =
      val result = Promise[(Int, js.Dictionary[js.Any], String)]()
      val onResponse: js.Function1[js.Dynamic, Unit] = response =>
        var content = ""
        response.setEncoding("utf8")
        response.on("data", ((chunk: String) => content += chunk): js.Function1[String, Unit])
        response.on("end", (() => {
          result.trySuccess((response.statusCode.asInstanceOf[Int], response.headers.asInstanceOf[js.Dictionary[js.Any]], content))
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
          assertEquals(body, "{\"data\":{\"message\":\"Hello Scala\"}}")
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
            val entry = js.JSON.parse(body).errors.asInstanceOf[js.Array[js.Dynamic]](0)
            assertEquals(entry.message.asInstanceOf[String], "Internal server error")
            if development then assert(entry.debug.cause.asInstanceOf[String].contains("private decoder detail"))
            else assertEquals(body, KyoTesting.opaque)
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
        assertEquals(recovered, (200, "{\"data\":{\"message\":\"recovered: Request body is not valid JSON\"}}"))
        assertEquals(rendered, (400, KyoTesting.errors("Request body is not valid JSON")))
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
          assertEquals(declared, (413, KyoTesting.errors("Request body is too large")))
          assertEquals(streamed, (413, KyoTesting.errors("Request body is too large")))
          assertEquals(fits, (200, "{\"data\":\"ok\"}"))
          assertEquals(started, 3)
          assertEquals(server.transportErrors, 0)
      }
    }

    test("typed JSON reads reject other media types with 415") {
      val handler = KHandler.define(request => request.body.json[Input].map(_.name))
      withServer(handler) { (_, port) =>
        val form = "name=Scala"
        post(port, List(form), Map("content-type" -> "application/x-www-form-urlencoded", "content-length" -> form.length.toString))
          .map(result => assertEquals(result, (415, KyoTesting.errors("Request body must be JSON"))))
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
        assertEquals(shape, (422, KyoTesting.errors("Request body does not match the expected shape")))
        assertEquals(defect, (500, KyoTesting.opaque))
    }

    test("the body is one-shot but reading it twice returns the same text") {
      val handler = KHandler.define { request =>
        for
          input <- request.body.json[Input]
          text <- request.body.text()
        yield Greeting(s"${input.name}|$text")
      }
      withServer(handler)((_, port) => post(port, "{\"name\":\"A\"}")).map { result =>
        assertEquals(result, (200, "{\"data\":{\"message\":\"A|{\\\"name\\\":\\\"A\\\"}\"}}"))
      }
    }

    // 4.2b: h3 sends a `null` result with sendNoContent in a plain app and in a preemptive
    // router (Nitro). `undefined` would fall through to h3's 404 in a plain app.
    test("a Unit body is a 204 without a body, in a plain h3 app and in a preemptive router") {
      val handler = KHandler.define(HandlerOptions(defaults = ResponseHead.noStore))(_ => Sync.defer(()))
      val created = KHandler.define(_ => Response.status(Status.Created))
      def check(routed: Boolean): Future[Unit] =
        for
          empty <- withServer(handler, routed = routed)((_, port) => exchange(port, Nil, Map.empty))
          withStatus <- withServer(created, routed = routed)((_, port) => exchange(port, Nil, Map.empty))
        yield
          val (status, headers, body) = empty
          assertEquals(status, 204, s"routed = $routed")
          assertEquals(body, "")
          assertEquals(headers.get("content-length"), None)
          assertEquals(headers.get("cache-control").map(_.toString), Some("no-store"))
          assertEquals((withStatus._1, withStatus._3), (201, ""), s"routed = $routed")
      check(routed = false).flatMap(_ => check(routed = true))
    }

    test("Raw and SingleOrList envelopes over HTTP") {
      val options = HandlerOptions(success = SuccessEnvelope.Raw, errors = ErrorEnvelope.SingleOrList)
      val handler = KHandler.define(options)(request => request.body.json[Input].map(input => Greeting(input.name)))
      withServer(handler) { (_, port) =>
        for
          ok <- post(port, "{\"name\":\"Raw\"}")
          invalid <- post(port, "{invalid json}")
        yield
          assertEquals(ok, (200, "{\"message\":\"Raw\"}"))
          assertEquals(invalid, (400, "{\"error\":{\"message\":\"Request body is not valid JSON\"}}"))
      }
    }

    test("HandlerOptions.bodyLimit limits json and text reads that do not pass a limit") {
      val options = HandlerOptions(bodyLimit = BodyLimit(16))
      val json = KHandler.define(options)(request => request.body.json[Input].map(_.name))
      val text = KHandler.define(options)(request => request.body.text().map(_.length))
      val explicit = KHandler.define(options)(request => request.body.json[Input](BodyLimit(1024)).map(_.name.length))
      val large = "{\"name\":\"" + ("x" * 32) + "\"}"
      for
        jsonLarge <- withServer(json)((_, port) => post(port, large))
        jsonStreamed <- withServer(json)((_, port) => post(port, List("{\"name\":\"", "x" * 10, "y" * 10, "\"}")))
        jsonSmall <- withServer(json)((_, port) => post(port, "{\"name\":\"ok\"}"))
        textLarge <- withServer(text)((_, port) => post(port, large))
        explicitLarge <- withServer(explicit)((_, port) => post(port, large))
      yield
        assertEquals(jsonLarge, (413, KyoTesting.errors("Request body is too large")))
        assertEquals(jsonStreamed, (413, KyoTesting.errors("Request body is too large")))
        assertEquals(jsonSmall, (200, "{\"data\":\"ok\"}"))
        assertEquals(textLarge, (413, KyoTesting.errors("Request body is too large")))
        assertEquals(explicitLarge, (200, "{\"data\":32}"))
    }

    test("debug mode from the Nitro record adds diagnostics over HTTP; requests cannot set it") {
      val handler = KHandler.define[String](_ => Abort.fail(new Exception("private detail")))
      def server(debug: Boolean) =
        val onRequest: js.Function1[js.Dynamic, Unit] = event =>
          event.context.updateDynamic("h34s")(js.Dynamic.literal(version = 1, development = false, debug = debug))
        val app = NativeH3.createApp(js.Dynamic.literal(onRequest = onRequest))
        app.use(handler.toNative)
        NodeHttp.createServer(NativeH3.toNodeListener(app))
      def request(debug: Boolean): Future[(Int, String)] =
        val native = server(debug)
        val listening = Promise[Int]()
        native.listen(0, "127.0.0.1", (() => { listening.success(native.address().port.asInstanceOf[Int]); () }): js.Function0[Unit])
        TestDeadline(listening.future.flatMap(port =>
          post(port, List("{}"), Map("content-type" -> "application/json", "x-h34s-debug" -> "true")),
        )).transformWith { result =>
          val closed = Promise[Unit]()
          native.close((() => { closed.trySuccess(()); () }): js.Function0[Unit])
          native.closeAllConnections()
          closed.future.flatMap(_ => Future.fromTry(result))
        }
      for
        withDebug <- request(debug = true)
        without <- request(debug = false)
      yield
        assertEquals(withDebug._1, 500)
        val entry = js.JSON.parse(withDebug._2).errors.asInstanceOf[js.Array[js.Dynamic]](0)
        assert(entry.debug.cause.asInstanceOf[String].contains("private detail"))
        assertEquals(without, (500, KyoTesting.opaque))
    }

    test("an absent or empty JSON body is a 422 for a model and None for an Option") {
      val model = KHandler.define(request => request.body.json[Input].map(_.name))
      val optional = KHandler.define(request => request.body.json[Option[Input]].map(_.fold("none")(_.name)))
      for
        emptyModel <- withServer(model)((_, port) => post(port, ""))
        chunkedModel <- withServer(model)((_, port) => post(port, Nil))
        emptyOptional <- withServer(optional)((_, port) => post(port, ""))
      yield
        assertEquals(emptyModel, (422, KyoTesting.errors("Request body does not match the expected shape")))
        assertEquals(chunkedModel, (422, KyoTesting.errors("Request body does not match the expected shape")))
        assertEquals(emptyOptional, (200, "{\"data\":\"none\"}"))
    }

    test("requireJsonContentType rejects a JSON body without a content type with 415") {
      val strict = KHandler.define(HandlerOptions(requireJsonContentType = true))(request => request.body.json[Input].map(_.name))
      val body = "{\"name\":\"Scala\"}"
      withServer(strict) { (_, port) =>
        for
          untyped <- post(port, List(body), Map("content-length" -> body.length.toString))
          typed <- post(port, body)
        yield
          assertEquals(untyped, (415, KyoTesting.errors("Request body must be JSON")))
          assertEquals(typed, (200, "{\"data\":\"Scala\"}"))
      }
    }

    // claude 2026-10-19: created: negative control for issue #41 (strict JSON content type by default)
    test("by default a JSON body without a content type is a 415; requireJsonContentType = false accepts it") {
      val default = KHandler.define(request => request.body.json[Input].map(_.name))
      val lenient = KHandler.define(HandlerOptions(requireJsonContentType = false))(request => request.body.json[Input].map(_.name))
      val body = "{\"name\":\"Scala\"}"
      val untyped = Map("content-length" -> body.length.toString)
      for
        rejected <- withServer(default)((_, port) => post(port, List(body), untyped))
        accepted <- withServer(lenient)((_, port) => post(port, List(body), untyped))
      yield
        assertEquals(rejected, (415, KyoTesting.errors("Request body must be JSON")))
        assertEquals(accepted, (200, "{\"data\":\"Scala\"}"))
    }

    test("Raw bodies never reach h3's type sniffing: text is text/plain, other values are JSON") {
      val raw = HandlerOptions(success = SuccessEnvelope.Raw)
      val text = KHandler.define(raw)(request => request.body.text().map(name => s"<b>$name</b>"))
      val buffer = KHandler.define(raw)(_ => Map("buffer" -> "1"))
      val typed = KHandler.define(raw)(_ => Response.header("content-type", "text/html; charset=utf-8").andThen("<p>ok</p>"))
      for
        html <- withServer(text)((_, port) => exchange(port, List("x"), Map("content-length" -> "1")))
        bytes <- withServer(buffer)((_, port) => exchange(port, Nil, Map.empty))
        chosen <- withServer(typed)((_, port) => exchange(port, Nil, Map.empty))
      yield
        assertEquals(html._1, 200)
        assertEquals(html._2.get("content-type").map(_.toString), Some("text/plain; charset=utf-8"))
        assertEquals(html._2.get("x-content-type-options").map(_.toString), Some("nosniff"))
        assertEquals(html._3, "<b>x</b>")
        assertEquals(bytes._1, 200)
        assertEquals(bytes._2.get("content-type").map(_.toString), Some("application/json"))
        assertEquals(bytes._3, "{\"buffer\":\"1\"}")
        assertEquals(chosen._2.get("content-type").map(_.toString), Some("text/html; charset=utf-8"))
    }

    /** Starts one request, waits for `started`, then destroys the client side of it. */
    private def abortAfter(port: Int, started: Future[Unit]): Future[Unit] =
      val request = NodeHttp.request(js.Dynamic.literal(hostname = "127.0.0.1", port = port, path = "/pending"), _ => ())
      // ECONNRESET is expected when this test destroys its own client request.
      request.on("error", ((_: js.Any) => ()): js.Function1[js.Any, Unit])
      request.end()
      started.map(_ => request.destroy())

    test("KHandler.uninterruptible: after a disconnect the step ends, then request finalizers run, then the request settles") {
      val events = mutable.ListBuffer.empty[String]
      val started = Promise[Unit]()
      val handler = KHandler.define[String] { _ =>
        Scope.acquireRelease(Sync.defer("connection"))(_ => Sync.defer { events += "connection released"; () }).map { _ =>
          KHandler.uninterruptible(
            Sync.defer { started.trySuccess(()); () }
              .andThen(Async.sleep(100.millis))
              .andThen(Sync.defer { events += "step done"; "committed" }),
          )
        }
      }
      withServer(handler) { (server, port) =>
        for
          _ <- abortAfter(port, started.future)
          _ <- server.completed.future
        yield
          assertEquals(events.toList, List("step done", "connection released"))
          assertEquals(server.transportErrors, 0)
      }
    }

    // Documents Kyo 1.0.0-RC7: `Async.uninterruptible` runs the step on an unscoped fiber. If this
    // test fails after a Kyo upgrade, revisit KHandler.uninterruptible and docs/lifetimes.md.
    test("Kyo's Async.uninterruptible alone does not make the request wait for the step") {
      val events = mutable.ListBuffer.empty[String]
      val started = Promise[Unit]()
      val stepDone = Promise[Unit]()
      val handler = KHandler.define[String] { _ =>
        Scope.acquireRelease(Sync.defer("connection"))(_ => Sync.defer { events += "connection released"; () }).map { _ =>
          Async.uninterruptible(
            Sync.defer { started.trySuccess(()); () }
              .andThen(Async.sleep(100.millis))
              .andThen(Sync.defer { events += "step done"; stepDone.trySuccess(()); "committed" }),
          )
        }
      }
      withServer(handler) { (server, port) =>
        for
          _ <- abortAfter(port, started.future)
          _ <- server.completed.future
          atSettle = events.toList
          _ <- stepDone.future
        yield
          assertEquals(atSettle, List("connection released"))
          assertEquals(events.toList, List("connection released", "step done"))
      }
    }

    /** A child that acquires a resource in its own scope (asynchronous release), then waits forever. */
    private def forkedOrder(fork: (String < (Async & Scope & Abort[Throwable])) => String < (Async & Abort[Throwable] & Scope)): Future[List[String]] =
      val events = mutable.ListBuffer.empty[String]
      val started = Promise[Unit]()
      val released = Promise[Unit]()
      val child: String < (Async & Scope & Abort[Throwable]) =
        Scope.acquireRelease(Sync.defer { started.trySuccess(()); () }) { _ =>
          Async.sleep(300.millis).andThen(Sync.defer { events += "child released"; released.trySuccess(()); () })
        }.andThen(Async.never[String])
      val handler = KHandler.define[String](_ => fork(child))
      withServer(handler) { (server, port) =>
        server.completed.future.foreach(_ => events += "settled")
        for
          _ <- abortAfter(port, started.future)
          _ <- server.completed.future
          _ <- released.future
        yield events.toList
      }

    // Review finding R3 expected Kyo RC7 to settle before this cleanup; it does not. The structured
    // combinators interrupt their children and wait for their (asynchronous) finalizers.
    test("cleanup inside work forked by timeout, race, gather, zip and foreach finishes before the request settles") {
      val variants: List[(String, (String < (Async & Scope & Abort[Throwable])) => String < (Async & Abort[Throwable] & Scope))] = List(
        "timeout" -> (child => Async.timeout(5.seconds)(Scope.run(child))),
        "race" -> (child => Async.race(Scope.run(child), Async.never[String])),
        "gather" -> (child => Async.gather(Scope.run(child), Async.never[String]).map(_.mkString)),
        "zip" -> (child => Async.zip(Scope.run(child), Async.never[String]).map(_._1)),
        "foreach" -> (child => Async.foreach(Seq(1, 2), concurrency = 2)(_ => Scope.run(child)).map(_.mkString)),
        "race-request-scope" -> (child => Async.race(child, Async.never[String])),
        "timeout-request-scope" -> (child => Async.timeout(5.seconds)(child)),
      )
      variants.foldLeft(Future.successful(List.empty[String])) { case (acc, (name, fork)) =>
        acc.flatMap(results => forkedOrder(fork).map(order => results :+ s"$name: ${order.mkString(" -> ")}"))
      }.map { results =>
        assertEquals(
          results,
          List(
            "timeout: child released -> settled",
            "race: child released -> settled",
            "gather: child released -> settled",
            "zip: child released -> settled",
            "foreach: child released -> child released -> settled",
            "race-request-scope: child released -> settled",
            "timeout-request-scope: child released -> settled",
          ),
        )
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
