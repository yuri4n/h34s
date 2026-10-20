package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.kyoh3.internal.NitroFetch
import kyo.*
import munit.FunSuite
import org.getshaka.nativeconverter.NativeConverter

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js

// claude 2026-10-19: created: the spike's in-process tests: KHandler.$fetch through Nitro 2.13.1's local fetch (node-mock-http over h3 toNodeListener), between h34s routes
/** Server-side `$fetch` between h34s routes through a copy of Nitro 2.13.1's local fetch
  * (`NitroLocal`). Each test measures and documents one behavior for the spike memo.
  */
class NitroLocalFetchSuite extends FunSuite:
    given ExecutionContext = ExecutionContext.global
    override val munitTimeout: FiniteDuration = 60.seconds

    final case class Greeting(message: String) derives NativeConverter
    final case class GreetingData(data: Greeting) derives NativeConverter
    final case class Input(name: String) derives NativeConverter

    /** What a caller handler observed; returned as its JSON body. */
    final case class Seen(
        kind: String,
        status: Int,
        marker: Option[String],
        contentType: Option[String],
        contentLength: Option[String],
        texts: List[String],
        times: List[Double],
        headAt: Double,
        producedAtHead: Int,
        decoded: String,
    ) derives NativeConverter

    private def kindOf(response: FetchResponse): String = response match
        case _: FetchResponse.Plain    => "plain"
        case _: FetchResponse.Streamed => "streamed"

    // claude 2026-10-20: changed: a streamed body may fail with a typed FetchError (ADR-0005, Q14)
    /** The body text of every chunk, with the time since `start` when each arrived. */
    private def read(response: FetchResponse, start: Double)(using Frame): Chunk[(String, Double)] < (Async & Scope & Abort[FetchError]) =
      response match
          case FetchResponse.Plain(_, _, text)    => Chunk((text, js.Date.now() - start))
          case FetchResponse.Streamed(_, _, body) => body.map(text => Sync.defer((text, js.Date.now() - start))).run

    private def seen(
        response: FetchResponse,
        chunks: Chunk[(String, Double)],
        headAt: Double,
        producedAtHead: Int = -1,
        decoded: String = "",
    ): Seen =
      Seen(
        kindOf(response), response.status, response.header("x-h34s-stream"), response.header("content-type"),
        response.header("content-length"), chunks.map(_._1).toList, chunks.map(_._2).toList, headAt, producedAtHead, decoded,
      )

    /** The `data` of an h34s JSON response that the test client received. */
    private def body(response: js.Dynamic): Future[js.Dynamic] =
      response.json().asInstanceOf[js.Promise[js.Dynamic]].toFuture.map(_.data)

    private def list(value: js.Any): List[js.Any] = value.asInstanceOf[js.Array[js.Any]].toList

    test("nitropack 2.13.1 resolves h3 1.15.5, node-mock-http 1.0.4 and ofetch 1.5.1 in this workspace") {
      assertEquals(NitroLocal.versions, Map("h3" -> "1.15.5", "node-mock-http" -> "1.0.4", "ofetch" -> "1.5.1"))
    }

    // (a)
    // claude 2026-10-20: changed: inverted: no marker and no stream content type is Plain, also without content-length (ADR-0005, Q2 option B)
    test("(a) JSON route in-process: no marker and no stream content type, so Plain without content-length; as[A] decodes it") {
      val app = NitroLocal.App()
      app.route("/api/greeting", KHandler.define(_ => Greeting("hi")))
      app.route(
        "/api/caller",
        KHandler.define { _ =>
          val start = js.Date.now()
          for
            raw <- KHandler.$fetch.raw("/api/greeting")
            headAt = js.Date.now() - start
            chunks <- read(raw, start)
            decoded <- KHandler.$fetch.get[GreetingData]("/api/greeting")
          yield seen(raw, chunks, headAt, decoded = decoded.data.message)
        },
      )
      TestDeadline(app.fetch("/api/caller").flatMap(body)).map { seen =>
        assertEquals(seen.status.asInstanceOf[Int], 200)
        assertEquals(seen.kind.asInstanceOf[String], "plain", "an in-process response without content-length is still Plain")
        assertEquals(seen.contentType.asInstanceOf[String], "application/json")
        assert(js.isUndefined(seen.contentLength) || seen.contentLength == null, js.JSON.stringify(seen))
        assert(js.isUndefined(seen.marker) || seen.marker == null, js.JSON.stringify(seen))
        assertEquals(list(seen.texts).mkString, "{\"data\":{\"message\":\"hi\"}}")
        assertEquals(seen.decoded.asInstanceOf[String], "hi")
      }
    }

    // claude 2026-10-20: changed: the name: content-length no longer decides (ADR-0005, Q2 option B)
    test("(a) the same JSON route over real HTTP (absolute URL): Plain as well, and only this path sets content-length") {
      val app = NitroLocal.App()
      app.route("/api/greeting", KHandler.define(_ => Greeting("hi")))
      TestDeadline(app.listen().flatMap { server =>
        app.route(
          "/api/caller",
          KHandler.define { _ =>
            val start = js.Date.now()
            for
              raw <- KHandler.$fetch.raw(server.url("/api/greeting"))
              headAt = js.Date.now() - start
              chunks <- read(raw, start)
              decoded <- raw match
                  case plain: FetchResponse.Plain => plain.as[GreetingData].map(_.data.message)
                  case _                          => Abort.fail(FetchError.Failed("expected Plain"))
            yield seen(raw, chunks, headAt, decoded = decoded)
          },
        )
        app.fetch("/api/caller").flatMap(body).transformWith(result => server.close().flatMap(_ => Future.fromTry(result)))
      }).map { seen =>
        assertEquals(seen.kind.asInstanceOf[String], "plain")
        assertEquals(seen.contentLength.asInstanceOf[String], "25")
        assertEquals(seen.decoded.asInstanceOf[String], "hi")
      }
    }

    // (b)
    test("(b) streamed Scala route in-process: $fetch returns head-first; the body is pulled in the caller's Scope") {
      var produced = 0
      val app = NitroLocal.App()
      val stream = Stream[String, Async] {
        def step(text: String) = Async.sleep(200.millis).andThen(Sync.defer { produced += 1 }).andThen(Emit.value(Chunk(text)))
        step("1\n").andThen(step("2\n")).andThen(step("3\n"))
      }
      app.route(
        "/api/stream",
        KHandler.define(_ => Response.header("content-type", "application/x-ndjson").andThen(Response.sendStream(stream))),
      )
      app.route(
        "/api/caller",
        KHandler.define { _ =>
          val start = js.Date.now()
          for
            raw <- KHandler.$fetch.raw("/api/stream")
            headAt = js.Date.now() - start
            producedAtHead = produced
            chunks <- read(raw, start)
          yield seen(raw, chunks, headAt, producedAtHead)
        },
      )
      TestDeadline(app.fetch("/api/caller").flatMap(body)).map { seen =>
        val times = list(seen.times).map(_.asInstanceOf[Double])
        val headAt = seen.headAt.asInstanceOf[Double]
        assertEquals(seen.kind.asInstanceOf[String], "streamed")
        assertEquals(seen.marker.asInstanceOf[String], "1")
        assertEquals(seen.contentType.asInstanceOf[String], "application/x-ndjson")
        assertEquals(list(seen.texts).mkString, "1\n2\n3\n")
        assertEquals(seen.producedAtHead.asInstanceOf[Int], 0, "the head arrived before the first element")
        assert(headAt < times.head, s"head after $headAt ms, chunks at $times ms")
        assert(times.last >= 550, s"chunks at $times ms")
        println(s"[spike] (b) in-process head after ${headAt.round} ms; chunks at ${times.map(_.round)} ms")
      }
    }

    // claude 2026-10-20: changed: sendStream flushes the head at the call, so the head arrives before the first element over a socket too (ADR-0004, Q1)
    test("(b) the same streamed route over real HTTP: the head arrives before the first element") {
      var produced = 0
      val app = NitroLocal.App()
      val stream = Stream[String, Async] {
        def step(text: String) = Async.sleep(200.millis).andThen(Sync.defer { produced += 1 }).andThen(Emit.value(Chunk(text)))
        step("1\n").andThen(step("2\n"))
      }
      app.route("/api/stream", KHandler.define(_ => Response.sendStream(stream)))
      TestDeadline(app.listen().flatMap { server =>
        val start = js.Date.now()
        js.Dynamic.global.fetch(server.url("/api/stream")).asInstanceOf[js.Promise[js.Dynamic]].toFuture.flatMap { response =>
          val headAt         = js.Date.now() - start
          val producedAtHead = produced
          val reader         = response.body.getReader()
          val decoder        = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)()
          def readAll(acc: List[(String, Double)]): Future[List[(String, Double)]] =
            reader.read().asInstanceOf[js.Promise[js.Dynamic]].toFuture.flatMap { step =>
              if step.done.asInstanceOf[Boolean] then Future.successful(acc.reverse)
              else readAll((decoder.decode(step.value).asInstanceOf[String], js.Date.now() - start) :: acc)
            }
          readAll(Nil).map { chunks =>
            (headAt, producedAtHead, chunks, response.headers.get("transfer-encoding"), response.headers.get("x-h34s-stream"))
          }
        }.transformWith(result => server.close().flatMap(_ => Future.fromTry(result)))
      }).map { (headAt, producedAtHead, chunks, transferEncoding, marker) =>
        val times = chunks.map(_._2)
        assertEquals(chunks.map(_._1).mkString, "1\n2\n")
        assertEquals(transferEncoding.asInstanceOf[String], "chunked")
        assertEquals(marker.asInstanceOf[String], "1")
        assertEquals(producedAtHead, 0, "the head arrived before the first element was produced")
        assert(headAt < times.head, s"head after $headAt ms, chunks at $times ms")
        assert(headAt < 150, s"head after $headAt ms; the first element takes 200 ms")
        println(s"[send-stream] (b) real HTTP head after ${headAt.round} ms; chunks at ${times.map(_.round)} ms")
      }
    }

    // (c)
    test("(c) a caller that drops a Streamed body: its Scope cancels the callee, whose finalizers run before the caller settles") {
      var streamReleased = 0
      var requestReleased = 0
      var pulled = 0
      val app = NitroLocal.App()
      val endless = Stream[String, Async & Scope] {
        Scope.ensure(Async.sleep(30.millis).andThen(Sync.defer { streamReleased += 1 }))
          .andThen(Sync.defer { pulled += 1 })
          .andThen(Emit.value(Chunk("x")))
          .andThen(Async.never[Unit])
      }
      app.route(
        "/api/endless",
        KHandler.define { _ =>
          Scope.ensure(Async.sleep(30.millis).andThen(Sync.defer { requestReleased += 1 })).andThen(Response.sendStream(endless))
        },
      )
      app.route(
        "/api/caller",
        KHandler.define(_ => KHandler.$fetch.raw("/api/endless").map(raw => s"${kindOf(raw)} unread")),
      )
      TestDeadline(app.fetch("/api/caller").flatMap(body)).map { data =>
        assertEquals(data.asInstanceOf[String], "streamed unread")
        assertEquals((streamReleased, requestReleased), (1, 1), "callee finalizers ran before the caller settled")
        println(s"[spike] (c) the dropped stream was pulled $pulled time(s) before the cancel")
      }
    }

    test("(c) a caller that reads part of a Streamed body: the rest is cancelled when its Scope closes") {
      var streamReleased = 0
      val app = NitroLocal.App()
      val endless = Stream[String, Async & Scope] {
        Scope.ensure(Sync.defer { streamReleased += 1 })
          .andThen(Loop.foreach(Emit.valueWith(Chunk("x"))(Loop.continue)))
      }
      app.route("/api/endless", KHandler.define(_ => Response.sendStream(endless)))
      app.route(
        "/api/caller",
        KHandler.define { _ =>
          KHandler.$fetch.raw("/api/endless").map {
            case FetchResponse.Streamed(_, _, body) => body.take(3).run.map(_.mkString)
            case other                              => s"unexpected ${kindOf(other)}"
          }
        },
      )
      TestDeadline(app.fetch("/api/caller").flatMap(body)).map { data =>
        assertEquals(data.asInstanceOf[String], "xxx")
        assertEquals(streamReleased, 1)
      }
    }

    // (d)
    test("(d) POST body forwarding: post[A] sends JSON with its own content type and length, not the incoming ones") {
      final case class Echo(name: String, contentType: Option[String], contentLength: Option[String]) derives NativeConverter
      final case class EchoData(data: Echo) derives NativeConverter
      final case class Pair(post: Echo, get: Echo) derives NativeConverter
      val app = NitroLocal.App()
      val strict = HandlerOptions(requireJsonContentType = true)
      app.route(
        "/api/echo",
        KHandler.define(strict) { request =>
          val name = if request.method == "POST" then request.body.json[Input].map(_.name) else Kyo.lift("")
          name.map(Echo(_, request.header("content-type"), request.header("content-length")))
        },
      )
      app.route(
        "/api/caller",
        KHandler.define { _ =>
          for
            post <- KHandler.$fetch.post[EchoData]("/api/echo", FetchBody.json(Input("Scala")))
            get <- KHandler.$fetch.get[EchoData]("/api/echo")
          yield Pair(post.data, get.data)
        },
      )
      val outer = js.Dynamic.literal(
        method = "POST",
        body = "hello",
        headers = js.Dynamic.literal("content-type" -> "text/plain", "content-length" -> "5"),
      )
      TestDeadline(app.fetch("/api/caller", outer).flatMap(body)).map { data =>
        assertEquals(data.post.name.asInstanceOf[String], "Scala")
        assertEquals(data.post.contentType.asInstanceOf[String], "application/json")
        assertEquals(data.post.contentLength.asInstanceOf[String], "16")
        assert(js.isUndefined(data.get.contentType) || data.get.contentType == null, js.JSON.stringify(data))
        assert(js.isUndefined(data.get.contentLength) || data.get.contentLength == null, js.JSON.stringify(data))
      }
    }

    // (e)
    test("(e) the incoming request's cookies and headers are forwarded as fetchWithEvent does; accept is not") {
      final case class Headers(
          cookie: Option[String],
          session: Option[String],
          bearer: Option[String],
          custom: Option[String],
          userAgent: Option[String],
          accept: Option[String],
          acceptEncoding: Option[String],
          host: Option[String],
      ) derives NativeConverter
      final case class HeadersData(data: Headers) derives NativeConverter
      val app = NitroLocal.App()
      app.route(
        "/api/headers",
        KHandler.define { request =>
          Headers(
            request.header("cookie"), request.cookie("session"), request.bearerToken, request.header("x-custom"),
            request.header("user-agent"), request.header("accept"), request.header("accept-encoding"), request.header("host"),
          )
        },
      )
      app.route("/api/caller", KHandler.define(_ => KHandler.$fetch.get[HeadersData]("/api/headers").map(_.data)))
      val outer = js.Dynamic.literal(headers = js.Dynamic.literal(
        "cookie" -> "session=abc; theme=dark",
        "authorization" -> "Bearer t0k3n",
        "x-custom" -> "1",
        "user-agent" -> "spike",
        "accept" -> "text/html",
        "accept-encoding" -> "gzip",
      ))
      TestDeadline(app.fetch("/api/caller", outer).flatMap(body)).map { data =>
        println(s"[spike] (e) the callee saw ${js.JSON.stringify(data)}")
        assertEquals(data.cookie.asInstanceOf[String], "session=abc; theme=dark")
        assertEquals(data.session.asInstanceOf[String], "abc")
        assertEquals(data.bearer.asInstanceOf[String], "t0k3n")
        assertEquals(data.custom.asInstanceOf[String], "1")
        assertEquals(data.userAgent.asInstanceOf[String], "spike")
        assert(js.isUndefined(data.acceptEncoding) || data.acceptEncoding == null, js.JSON.stringify(data))
        assert(js.isUndefined(data.accept) || data.accept == null, js.JSON.stringify(data))
      }
    }

    // claude 2026-10-20: changed: inverted: an absolute URL gets only the caller's headers and its body's entity headers (ADR-0005, Q5 option B)
    test("an absolute URL gets only the headers the caller passes: the incoming cookie, authorization and entity headers stay behind") {
      final case class Forwarded(
          cookie: Option[String],
          authorization: Option[String],
          custom: Option[String],
          caller: Option[String],
          contentType: Option[String],
          contentLength: Option[String],
          name: String,
          host: Option[String],
      ) derives NativeConverter
      final case class ForwardedData(data: Forwarded) derives NativeConverter
      final case class Pair(get: Forwarded, post: Forwarded) derives NativeConverter
      val app = NitroLocal.App()
      app.route(
        "/api/headers",
        KHandler.define { request =>
          val name = if request.method == "POST" then request.body.json[Input].map(_.name) else Kyo.lift("")
          name.map(name =>
            Forwarded(
              request.header("cookie"), request.header("authorization"), request.header("x-custom"), request.header("x-caller"),
              request.header("content-type"), request.header("content-length"), name, request.header("host"),
            ),
          )
        },
      )
      TestDeadline(app.listen().flatMap { server =>
        val target = server.url("/api/headers")
        app.route(
          "/api/caller",
          KHandler.define { _ =>
            for
              get <- KHandler.$fetch.get[ForwardedData](target, headers = Seq("X-Caller" -> "1"))
              post <- KHandler.$fetch.post[ForwardedData](target, FetchBody.json(Input("Scala")), headers = Seq("x-caller" -> "2"))
            yield Pair(get.data, post.data)
          },
        )
        val outer = js.Dynamic.literal(
          method = "POST",
          body = "hello",
          headers = js.Dynamic.literal(
            "cookie" -> "session=abc",
            "authorization" -> "Bearer t0k3n",
            "x-custom" -> "1",
            "content-type" -> "text/plain",
            "content-length" -> "5",
          ),
        )
        app.fetch("/api/caller", outer).flatMap(body).map(data => (data, server.port))
          .transformWith(result => server.close().flatMap(_ => Future.fromTry(result)))
      }).map { (data, port) =>
        def absent(value: js.Dynamic): Boolean = js.isUndefined(value) || value == null
        for forwarded <- List(data.get, data.post) do
            assert(absent(forwarded.cookie), js.JSON.stringify(data))
            assert(absent(forwarded.authorization), js.JSON.stringify(data))
            assert(absent(forwarded.custom), js.JSON.stringify(data))
            assertEquals(forwarded.host.asInstanceOf[String], s"127.0.0.1:$port")
        assertEquals(data.get.caller.asInstanceOf[String], "1")
        assert(absent(data.get.contentType), js.JSON.stringify(data))
        assert(absent(data.get.contentLength), js.JSON.stringify(data))
        assertEquals(data.post.caller.asInstanceOf[String], "2")
        assertEquals(data.post.contentType.asInstanceOf[String], "application/json")
        assertEquals(data.post.contentLength.asInstanceOf[String], "16")
        assertEquals(data.post.name.asInstanceOf[String], "Scala")
      }
    }

    // claude 2026-10-19: created: a network failure is a typed FetchError.Failed, not an unhandled rejection
    test("an absolute URL that cannot be reached is a typed FetchError.Failed") {
      val app = NitroLocal.App()
      TestDeadline(app.listen().flatMap { server =>
        val closed = server.url("/api/gone")
        server.close().flatMap { _ =>
          app.route(
            "/api/caller",
            KHandler.define(_ =>
              Abort.run[FetchError](KHandler.$fetch.raw(closed)).map(_.fold(_ => "response", {
                case FetchError.Failed(_) => "failed"
                case other                => other.toString
              }, _ => "panic")),
            ),
          )
          app.fetch("/api/caller").flatMap(body)
        }
      }).map(data => assertEquals(data.asInstanceOf[String], "failed"))
    }

    test("a callee error status is a typed FetchError.Status for text/as, and a plain status for raw") {
      val app = NitroLocal.App()
      app.route("/api/missing", KHandler.define(_ => KHandler.failWith(404, "No such note"): Unit < HandlerEffects))
      app.route(
        "/api/caller",
        KHandler.define { _ =>
          for
            raw <- KHandler.$fetch.raw("/api/missing")
            text <- Abort.run[FetchError](KHandler.$fetch.text("/api/missing"))
          yield s"${raw.status} ${text.fold(_ => "ok", {
              case FetchError.Status(status, body) => s"Status($status, $body)"
              case other                           => other.toString
            }, _ => "panic")}"
        },
      )
      TestDeadline(app.fetch("/api/caller").flatMap(body)).map { data =>
        assertEquals(data.asInstanceOf[String], "404 Status(404, {\"errors\":[{\"message\":\"No such note\"}]})")
      }
    }

    // claude 2026-10-20: created: the Kyo guide's $fetch example (docs/content/1.guide/03.kyo.md, "Call other routes with $fetch"); keep them equal
    test("the guide's $fetch example: get decodes the envelope, and raw streams a body that the caller reads in part") {
      val app = NitroLocal.App()
      app.route("/api/greeting", KHandler.define(_ => Greeting("hi")))
      app.route(
        "/api/events",
        KHandler.define(_ =>
          Response.header("content-type", "application/x-ndjson").andThen(Response.sendStream(Stream.init(Seq("1\n", "2\n", "3\n")))),
        ),
      )
      // region guide-kyo-fetch-call
      val summary: Request => String < HandlerEffects = _ =>
        for
          greeting <- KHandler.$fetch.get[GreetingData]("/api/greeting")
          events   <- KHandler.$fetch.raw("/api/events")
          first    <- events match
              case FetchResponse.Streamed(_, _, body) =>
                Abort.run[FetchError](body.take(2).run).map(_.foldError(_.mkString, _ => "the stream broke"))
              case plain: FetchResponse.Plain => Kyo.lift(plain.text)
        yield s"${greeting.data.message}: $first"
      // endregion guide-kyo-fetch-call
      app.route("/api/summary", KHandler.define(summary))
      TestDeadline(app.fetch("/api/summary").flatMap(body)).map(data => assertEquals(s"$data", "hi: 1\n2\n"))
    }

    // claude 2026-10-20: created: the app.baseURL prefix as a pure function, compared with ufo's withBase, which Nuxt's $fetch uses (ADR-0005, decision 1)
    test("app.baseURL: an in-process path gets the base once, with one slash at the join, as ufo's withBase does") {
      val ufo = NitroLocal.requireFromNitro("ufo")
      val cases = List(
        ("/api/x", "/", "/api/x"),
        ("/api/x", "", "/api/x"),
        ("/api/x", "/app/", "/app/api/x"),
        ("/api/x", "/app", "/app/api/x"),
        ("/api/x?q=1", "/app/", "/app/api/x?q=1"),
        ("/api/x", "/a/b/", "/a/b/api/x"),
        ("/app/api/x", "/app/", "/app/api/x"),
        ("/app", "/app/", "/app"),
        ("/app?q=1", "/app/", "/app?q=1"),
        ("/apple", "/app/", "/app/apple"),
      )
      for (path, base, expected) <- cases do
          assertEquals(NitroFetch.withBase(path, base), expected, s"withBase($path, $base)")
          assertEquals(ufo.withBase(path, base).asInstanceOf[String], expected, s"ufo withBase($path, $base)")
      // Two choices of h34s where ufo differs: a base with several trailing slashes keeps one slash at
      // the join, and a base without a leading slash gets one, so the path stays in-process.
      assertEquals(NitroFetch.withBase("/api/x", "/app//"), "/app/api/x")
      assertEquals(NitroFetch.withBase("/api/x", "app/"), "/app/api/x")
    }

    // claude 2026-10-20: created: Nitro mounts its routes under app.baseURL; an in-process $fetch reaches them (ADR-0005, decision 1)
    test("app.baseURL: an in-process $fetch reaches a route under a non-root base; a path that carries the base is not prefixed again") {
      val app = NitroLocal.App(baseURL = "/app/")
      app.route("/api/greeting", KHandler.define(_ => Greeting("hi")))
      app.route(
        "/api/caller",
        KHandler.define { _ =>
          for
            short <- KHandler.$fetch.get[GreetingData]("/api/greeting")
            full <- KHandler.$fetch.raw("/app/api/greeting")
          yield s"${short.data.message} ${full.status}"
        },
      )
      TestDeadline(NitroLocal.withBaseURL("/app/")(app.fetch("/app/api/caller").flatMap(body))).map { data =>
        // Interpolated: without the prefix the caller answers 500 and data is undefined.
        assertEquals(s"$data", "hi 200")
      }
    }

    // claude 2026-10-20: created: the in-process call passes only _platform and waitUntil (ADR-0005, decision 1)
    test("an in-process call passes only the caller's _platform and waitUntil: the callee cannot read the caller's other context entries") {
      final case class Context(unenv: List[String], context: List[String], platform: String, waitUntil: String, session: Option[String])
          derives NativeConverter
      final case class ContextData(data: Context) derives NativeConverter
      val app = NitroLocal.App()
      // An authentication middleware of the caller's app puts the user's session into its context.
      app.onRequest(event => if event.path.asInstanceOf[String] == "/api/caller" then event.context.session = "secret-session")
      app.routeNative(
        "/api/context",
        event => {
          val unenv   = event.node.req.__unenv__
          val keys    = (value: js.Dynamic) => js.Object.keys(value.asInstanceOf[js.Object]).toList.sorted
          val context = event.context
          js.Dynamic.literal(
            data = js.Dynamic.literal(
              unenv = js.Array(keys(unenv)*),
              context = js.Array(keys(context)*),
              platform = context.edge,
              waitUntil = js.typeOf(context.waitUntil),
              session = if js.isUndefined(unenv.session) then context.session else unenv.session,
            ),
          )
        },
      )
      app.route("/api/caller", KHandler.define(_ => KHandler.$fetch.get[ContextData]("/api/context").map(_.data)))
      val waitUntil: js.Function1[js.Any, Unit] = _ => ()
      val outer = js.Dynamic.literal(context = js.Dynamic.literal(_platform = js.Dynamic.literal(edge = "edge-1"), waitUntil = waitUntil))
      TestDeadline(app.fetch("/api/caller", outer).flatMap(body)).map { data =>
        assertEquals(list(data.unenv).map(_.toString), List("_platform", "waitUntil"))
        assert(!list(data.context).map(_.toString).contains("session"), js.JSON.stringify(data))
        assert(js.isUndefined(data.session) || data.session == null, js.JSON.stringify(data))
        assertEquals(data.platform.asInstanceOf[String], "edge-1", "Nitro spreads _platform into the callee's context")
        assertEquals(data.waitUntil.asInstanceOf[String], "function")
      }
    }

    // claude 2026-10-20: created: a callee stream that fails after its head is a typed FetchError.BodyFailed (ADR-0005, Q14)
    test("a callee stream that fails after its head: the caller's read fails with a typed FetchError.BodyFailed, not a panic") {
      val app = NitroLocal.App()
      val failing = Stream.init(Seq("one\n")).concat(Stream[String, Abort[Exception]](Abort.fail(Exception("private detail of the callee"))))
      app.route("/api/failing", KHandler.define(_ => Response.status(Status.Accepted).andThen(Response.sendStream(failing))))
      app.route(
        "/api/caller",
        KHandler.define { _ =>
          def outcome(result: Result[FetchError, Any]): String = result.fold(
            _ => "ended",
            {
              case FetchError.BodyFailed(status, reason) => s"BodyFailed($status, $reason)"
              case other                                 => other.toString
            },
            panic => s"panic $panic",
          )
          for
            raw <- KHandler.$fetch.raw("/api/failing")
            read <- raw match
                case FetchResponse.Streamed(status, _, body) =>
                  var seen = Vector.empty[String]
                  Abort.run[FetchError](body.foreach(text => Sync.defer { seen = seen :+ text }))
                    .map(result => s"$status ${seen.mkString.trim} ${outcome(result)}")
                case other => Kyo.lift(s"unexpected ${kindOf(other)}")
            text <- Abort.run[FetchError](KHandler.$fetch.text("/api/failing"))
          yield s"$read | ${outcome(text)}"
        },
      )
      TestDeadline(app.fetch("/api/caller").flatMap(response => body(response).map(data => (response.status, data)))).map { (status, data) =>
        val reason = "the body ended with an error before its end"
        assertEquals(status.asInstanceOf[Int], 200, "the caller handled the Abort")
        assertEquals(data.asInstanceOf[String], s"202 one BodyFailed(202, $reason) | BodyFailed(202, $reason)")
        assert(!data.asInstanceOf[String].contains("private detail"))
      }
    }

    // claude 2026-10-20: created: headerValues keeps every set-cookie value; headers joins them (ADR-0005, Q14)
    test("headerValues keeps each set-cookie of the callee apart, in-process and over HTTP; headers joins them") {
      final case class Cookies(kind: String, values: List[String], joined: String, tags: List[String]) derives NativeConverter
      final case class Pair(local: Cookies, network: Cookies) derives NativeConverter
      def cookies(kind: String, response: FetchResponse): Cookies =
        Cookies(kind, response.headerValues("Set-Cookie"), response.header("set-cookie").getOrElse(""), response.headerValues("x-tag"))
      val app = NitroLocal.App()
      app.route(
        "/api/cookies",
        KHandler.define { _ =>
          Response.cookie(SetCookie("a", "1")).andThen(Response.cookie(SetCookie("b", "2", maxAge = Some(60))))
            .andThen(Response.appendHeader("x-tag", "one")).andThen(Response.appendHeader("x-tag", "two"))
        },
      )
      TestDeadline(app.listen().flatMap { server =>
        app.route(
          "/api/caller",
          KHandler.define { _ =>
            for
              local <- KHandler.$fetch.raw("/api/cookies")
              network <- KHandler.$fetch.raw(server.url("/api/cookies"))
            yield Pair(cookies("local", local), cookies("network", network))
          },
        )
        app.fetch("/api/caller").flatMap(body).transformWith(result => server.close().flatMap(_ => Future.fromTry(result)))
      }).map { data =>
        for seen <- List(data.local, data.network) do
            val values = list(seen.values).map(_.toString)
            assertEquals(values.size, 2, js.JSON.stringify(seen))
            assert(values.head.startsWith("a=1;") && values(1).startsWith("b=2;"), js.JSON.stringify(seen))
            assertEquals(seen.joined.asInstanceOf[String], values.mkString(", "))
            assertEquals(list(seen.tags).map(_.toString), List("one, two"), "the Fetch API joins other repeated headers")
      }
    }

    // claude 2026-10-20: created: headerValues on a fake response: set-cookie is split as Nitro splits it, the comma of an Expires date stays (ADR-0005, Q14)
    test("headerValues splits a joined set-cookie as Nitro does, keeping the comma of an Expires date; other headers are one value") {
      val response = FetchResponse.Plain(
        200,
        Map("set-cookie" -> "a=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/, b=2", "vary" -> "accept, origin"),
        "",
      )
      assertEquals(response.headerValues("Set-Cookie"), List("a=1; Expires=Wed, 21 Oct 2026 07:28:00 GMT; Path=/", "b=2"))
      assertEquals(response.headerValues("vary"), List("accept, origin"))
      assertEquals(response.headerValues("x-missing"), Nil)
    }

    // claude 2026-10-20: created: the untyped verbs decode the JSON as it arrives, envelope included (ADR-0005, Q15)
    test("get[A] decodes the JSON as it arrives: a model of the { data: ... } envelope sees the value, a bare model does not") {
      final case class MaybeGreeting(message: Option[String]) derives NativeConverter
      val app = NitroLocal.App()
      app.route("/api/greeting", KHandler.define(_ => Greeting("hi")))
      app.route(
        "/api/caller",
        KHandler.define { _ =>
          for
            enveloped <- KHandler.$fetch.get[GreetingData]("/api/greeting")
            bare <- KHandler.$fetch.get[MaybeGreeting]("/api/greeting")
          yield s"${enveloped.data.message} ${bare.message}"
        },
      )
      TestDeadline(app.fetch("/api/caller").flatMap(body)).map { data =>
        assertEquals(data.asInstanceOf[String], "hi None", "get keeps the envelope: the bare model finds no message at the top")
      }
    }

    // ---- Server-sent events: Response.sendEvents and $fetch.events (ADR-0007) ----

    // claude 2026-10-20: created: the typed event of the server-sent event tests (ADR-0007)
    final case class Tick(remaining: Int) derives NativeConverter
    final case class Countdown(remaining: Int) derives NativeConverter
    final case class TicksSeen(remaining: List[Int], times: List[Double], headAt: Double, producedAtHead: Int) derives NativeConverter

    /** An event as text, for the callers' JSON bodies: `type|id|data`. */
    private def shown(event: ServerEvent): String = s"${event.event.getOrElse("message")}|${event.id.getOrElse("-")}|${event.data}"

    /** A failure as text, for the callers' JSON bodies. */
    private def failure(result: Result[FetchError, Any]): String = result.fold(
      _ => "ended",
      {
        case FetchError.NotEvents(status, contentType) => s"NotEvents($status, ${contentType.getOrElse("-")})"
        case FetchError.Status(status, body)           => s"Status($status, $body)"
        case FetchError.Decode(status, reason)         => s"Decode($status, $reason)"
        case other                                     => other.getClass.getSimpleName
      },
      panic => s"panic $panic",
    )

    // claude 2026-10-20: created: a Scala callee's sendEvents read by $fetch.events[E] in-process, head-first (ADR-0007)
    test("sendEvents in-process: $fetch.events[E] returns head-first and decodes the typed events as they arrive") {
      var produced = 0
      val app = NitroLocal.App()
      // One element per chunk: Stream.map emits a chunk when all of its elements are mapped.
      val ticks = Stream.init(Seq(3, 2, 1), chunkSize = 1).map { remaining =>
        Async.sleep(200.millis).andThen(Sync.defer { produced += 1; ServerEvent.json(Tick(remaining), event = Some("tick")) })
      }
      app.route("/api/ticks", KHandler.define(_ => Response.sendEvents(ticks)))
      app.route(
        "/api/caller",
        KHandler.define { _ =>
          val start = js.Date.now()
          for
            events <- KHandler.$fetch.events[Tick]("/api/ticks")
            headAt = js.Date.now() - start
            producedAtHead = produced
            seen <- events.map(tick => Sync.defer((tick.remaining, js.Date.now() - start))).run
          yield TicksSeen(seen.map(_._1).toList, seen.map(_._2).toList, headAt, producedAtHead)
        },
      )
      TestDeadline(app.fetch("/api/caller").flatMap(body)).map { seen =>
        val times  = list(seen.times).map(_.asInstanceOf[Double])
        val headAt = seen.headAt.asInstanceOf[Double]
        assertEquals(list(seen.remaining).map(_.asInstanceOf[Int]), List(3, 2, 1))
        assertEquals(seen.producedAtHead.asInstanceOf[Int], 0, "the head arrived before the first event")
        assert(headAt < times.head, s"head after $headAt ms, events at $times ms")
        assert(times.zip(times.tail).forall((earlier, later) => later - earlier >= 150), s"each event as it is sent: $times ms")
      }
    }

    // claude 2026-10-20: created: the streaming guide's caller example (docs/content/1.guide/09.streaming.md, "Read the events in a handler"); docs/test checks that they are equal (ADR-0007)
    test("the guide's events example: a handler reads the countdown's typed events with $fetch.events") {
      val app = NitroLocal.App()
      val countdown = Stream.init(3 to 0 by -1).map(remaining => ServerEvent.json(Countdown(remaining), event = Some("tick")))
      app.route("/api/kyo/countdown", KHandler.define(_ => Response.sendEvents(countdown)))
      // #region kyo-events
      val total: EventHandler = KHandler.define: _ =>
          for
            ticks <- KHandler.$fetch.events[Countdown]("/api/kyo/countdown")
            seen  <- ticks.run
          yield seen.map(_.remaining).sum
      // #endregion kyo-events
      app.route("/api/total", total)
      TestDeadline(app.fetch("/api/total").flatMap(body)).map(data => assertEquals(data.asInstanceOf[Int], 6))
    }

    // claude 2026-10-20: created: a JavaScript route with h3's createEventStream read by the untyped $fetch.events (ADR-0007; the content-type path of ADR-0005, Q2)
    test("a JavaScript route with h3's createEventStream: the untyped $fetch.events reads its events by the content type") {
      val app = NitroLocal.App()
      app.routeEventStream("/api/js-clock", js.Array(js.Dynamic.literal(event = "tick", id = "1", data = "one"), "two"))
      app.route(
        "/api/caller",
        KHandler.define { _ =>
          // The route sets no x-h34s-stream marker: only its content type makes it an event stream.
          // The test reads the body to its end: a cancel of h3's createEventStream body leaks
          // unhandled rejections in-process (ADR-0007).
          KHandler.$fetch.events(FetchRequest("/api/js-clock")).map(_.run).map(_.map(shown).mkString(" ; "))
        },
      )
      TestDeadline(app.fetch("/api/caller").flatMap(body)).map { data =>
        assertEquals(data.asInstanceOf[String], "tick|1|one ; message|-|two")
      }
    }

    // claude 2026-10-20: created: events refuses a response that is not an event stream with a typed FetchError (ADR-0007)
    test("events of a route that is not an event stream fails with FetchError.NotEvents; an error status with FetchError.Status") {
      val app = NitroLocal.App()
      app.route("/api/greeting", KHandler.define(_ => Greeting("hi")))
      app.route("/api/missing", KHandler.define(_ => KHandler.failWith(404, "No such stream"): Unit < HandlerEffects))
      app.route(
        "/api/caller",
        KHandler.define { _ =>
          for
            json <- Abort.run[FetchError](KHandler.$fetch.events[Tick]("/api/greeting"))
            missing <- Abort.run[FetchError](KHandler.$fetch.events(FetchRequest("/api/missing")))
          yield s"${failure(json)} | ${failure(missing)}"
        },
      )
      TestDeadline(app.fetch("/api/caller").flatMap(body)).map { data =>
        assertEquals(
          data.asInstanceOf[String],
          "NotEvents(200, application/json) | Status(404, {\"errors\":[{\"message\":\"No such stream\"}]})",
        )
      }
    }

    // claude 2026-10-20: created: an event whose data is not JSON for E fails the stream with FetchError.Decode, after the events before it (ADR-0007)
    test("a typed event whose data is not JSON for E fails the stream with FetchError.Decode, which names the event") {
      val app = NitroLocal.App()
      app.route(
        "/api/mixed",
        KHandler.define(_ => Response.sendEvents(Stream.init(Seq(ServerEvent.json(Tick(3)), ServerEvent("not json", event = Some("oops")), ServerEvent.json(Tick(1)))))),
      )
      app.route(
        "/api/caller",
        KHandler.define { _ =>
          var seen = Vector.empty[Int]
          KHandler.$fetch.events[Tick]("/api/mixed").map { events =>
            Abort.run[FetchError](events.foreach(tick => Sync.defer { seen = seen :+ tick.remaining }))
              .map(result => s"${seen.mkString(",")} ${failure(result)}")
          }
        },
      )
      TestDeadline(app.fetch("/api/caller").flatMap(body)).map { data =>
        assertEquals(data.asInstanceOf[String], "3 Decode(200, the data of event 2 (\"oops\") is not valid JSON)")
      }
    }

    // claude 2026-10-20: created: frames split across chunks over real HTTP decode the same; events sends accept: text/event-stream (ADR-0007)
    test("over real HTTP (absolute URL), events split inside lines and inside a CRLF decode to the same typed events") {
      val app   = NitroLocal.App()
      val split = Seq("data: {\"remain", "ing\":2}\r", "\n\r\nda", "ta: {\"remaining\":1}\n", "\n", ": done\n")
      app.route(
        "/api/split",
        KHandler.define { request =>
          if request.header("accept").contains("text/event-stream") then
              Response.header("content-type", "text/event-stream").andThen(Response.sendStream(Stream.init(split)))
          else KHandler.failWith(406, "events only"): Sent < HandlerEffects
        },
      )
      TestDeadline(app.listen().flatMap { server =>
        app.route(
          "/api/caller",
          KHandler.define(_ => KHandler.$fetch.events[Tick](server.url("/api/split")).map(_.run).map(_.map(_.remaining).mkString(","))),
        )
        app.fetch("/api/caller").flatMap(body).transformWith(result => server.close().flatMap(_ => Future.fromTry(result)))
      }).map(data => assertEquals(data.asInstanceOf[String], "2,1"))
    }

    // claude 2026-10-20: created: the negative control of review B1: the body's one UTF-8 decode removes one byte order mark, and the decoder removes none (ADR-0007)
    test("events: the body is decoded as UTF-8 once, so of two leading byte order marks only the first is removed") {
      val app = NitroLocal.App()
      def sending(text: String): EventHandler =
        KHandler.define(_ => Response.header("content-type", "text/event-stream").andThen(Response.sendStream(Stream.init(Seq(text)))))
      // In-process, h34s sends the text as UTF-8 bytes: EF BB BF for each U+FEFF.
      app.route("/api/one-bom", sending("\uFEFFdata: a\n\n"))
      // A WHATWG parser reads the first line's field name as "\uFEFFdata", an unknown field.
      app.route("/api/two-boms", sending("\uFEFF\uFEFFdata: a\n\ndata: b\n\n"))
      app.route(
        "/api/caller",
        KHandler.define { _ =>
          for
            one <- KHandler.$fetch.events(FetchRequest("/api/one-bom")).map(_.run)
            two <- KHandler.$fetch.events(FetchRequest("/api/two-boms")).map(_.run)
          yield s"${one.map(_.data).mkString(",")} | ${two.map(_.data).mkString(",")}"
        },
      )
      TestDeadline(app.fetch("/api/caller").flatMap(body)).map(data => assertEquals(data.asInstanceOf[String], "a | b"))
    }

    // claude 2026-10-20: created: a JavaScript body of strings gets the one byte order mark removal of a UTF-8 decode, as its bytes would (review B1, ADR-0007)
    test("events: a JavaScript stream of strings loses one leading byte order mark, as its UTF-8 bytes would") {
      val app = NitroLocal.App()
      // A route that returns a ReadableStream of strings: in-process, its chunks reach $fetch as strings.
      val strings: js.Function1[js.Dynamic, js.Any] = event => {
        event.node.res.setHeader("content-type", "text/event-stream")
        val start: js.Function1[js.Dynamic, Unit] = controller => {
          Seq("\uFEFFdata: a\n\n", "\uFEFFdata: b\n\n", "data: c\n\n").foreach(chunk => controller.enqueue(chunk))
          controller.close()
          ()
        }
        js.Dynamic.newInstance(js.Dynamic.global.ReadableStream)(js.Dynamic.literal(start = start))
      }
      app.routeNative("/api/js-strings", strings)
      app.route(
        "/api/caller",
        KHandler.define(_ => KHandler.$fetch.events(FetchRequest("/api/js-strings")).map(_.run).map(_.map(_.data).mkString(","))),
      )
      TestDeadline(app.fetch("/api/caller").flatMap(body)).map(data => assertEquals(data.asInstanceOf[String], "a,c"))
    }
end NitroLocalFetchSuite
