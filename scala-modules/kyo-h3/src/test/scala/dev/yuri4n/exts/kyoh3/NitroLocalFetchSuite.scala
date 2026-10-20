package dev.yuri4n.exts.kyoh3

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

    /** The body text of every chunk, with the time since `start` when each arrived. */
    private def read(response: FetchResponse, start: Double)(using Frame): Chunk[(String, Double)] < (Async & Scope) =
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
end NitroLocalFetchSuite
