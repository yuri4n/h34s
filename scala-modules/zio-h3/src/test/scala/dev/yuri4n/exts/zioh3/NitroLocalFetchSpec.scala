package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.kyoh3.{NitroGlobal, NitroLocal}
import org.getshaka.nativeconverter.NativeConverter
import zio.*
import zio.stream.ZStream
import zio.test.*

import scala.concurrent.ExecutionContext
import scala.scalajs.js

// claude 2026-10-20: created: the ZIO $fetch through Nitro 2.13.1's local fetch, mirroring the Kyo NitroLocalFetchSuite (ADR-0005 gap 6)
/** `ZHandler.$fetch` between h34s routes through the copy of Nitro 2.13.1's local fetch that the Kyo
  * core's `NitroLocalFetchSuite` uses (`NitroLocal`). The callers and most callees are ZIO handlers.
  */
object NitroLocalFetchSpec extends ZIOSpecDefault:
    given ExecutionContext = ExecutionContext.global

    final case class Greeting(message: String) derives NativeConverter
    final case class GreetingData(data: Greeting) derives NativeConverter
    final case class Input(name: String) derives NativeConverter

    /** What a caller handler observed; returned as its JSON body. */
    final case class Seen(kind: String, status: Int, marker: Option[String], texts: List[String], times: List[Double], headAt: Double, producedAtHead: Int)
        derives NativeConverter

    private def kindOf(response: FetchResponse): String = response match
        case _: FetchResponse.Plain    => "plain"
        case _: FetchResponse.Streamed => "streamed"

    /** The caller's `data`, decoded; fails with the whole body when the caller did not answer 200. */
    private def ok[A: NativeConverter](app: NitroLocal.App, path: String, init: js.Dynamic = js.Dynamic.literal()): Task[A] =
      for
        response <- ZIO.fromFuture(_ => app.fetch(path, init))
        json <- ZIO.fromPromiseJS(response.json().asInstanceOf[js.Promise[js.Dynamic]])
        _ <- ZIO.when(response.status.asInstanceOf[Int] != 200)(ZIO.fail(Exception(s"${response.status}: ${js.JSON.stringify(json)}")))
        value <- ZIO.attempt(summon[NativeConverter[A]].fromNative(json.data))
      yield value

    private def closing[A](server: NitroLocal.Server)(test: Task[A]): Task[A] =
      test.onExit(_ => ZIO.fromFuture(_ => server.close()).orDie)

    // claude 2026-10-20: created: the typed event of the server-sent event tests and what a caller saw (ADR-0007)
    final case class Tick(remaining: Int) derives NativeConverter
    final case class TicksSeen(remaining: List[Int], times: List[Double], headAt: Double, producedAtHead: Int) derives NativeConverter

    /** An event as text, for the callers' JSON bodies: `type|id|data`. */
    private def shown(event: ServerEvent): String = s"${event.event.getOrElse("message")}|${event.id.getOrElse("-")}|${event.data}"

    /** A failure as text, for the callers' JSON bodies. */
    private def failure(result: Either[FetchError, Any]): String = result match
        case Right(_)                                        => "ended"
        case Left(FetchError.NotEvents(status, contentType)) => s"NotEvents($status, ${contentType.getOrElse("-")})"
        case Left(FetchError.Status(status, body))           => s"Status($status, $body)"
        case Left(FetchError.Decode(status, reason))         => s"Decode($status, $reason)"
        case Left(other)                                     => other.getClass.getSimpleName

    def spec = suite("ZHandler.$fetch through Nitro's local fetch")(
      test("a streamed ZIO route in-process: raw returns head-first, and the body is a ZStream pulled on demand") {
        var produced = 0
        val app      = NitroLocal.App()
        def step(text: String) = ZStream.fromZIO(ZIO.sleep(200.millis) *> ZIO.succeed { produced += 1; text })
        val stream   = step("1\n") ++ step("2\n") ++ step("3\n")
        app.route(
          "/api/stream",
          ZHandler.define(_ => Response.header("content-type", "application/x-ndjson") *> Response.sendStream(stream)),
        )
        app.route(
          "/api/caller",
          ZHandler.define { _ =>
            val start = js.Date.now()
            for
              raw <- ZHandler.$fetch.raw("/api/stream")
              headAt = js.Date.now() - start
              producedAtHead = produced
              chunks <- raw match
                  case FetchResponse.Streamed(_, _, body) => body.map(text => (text, js.Date.now() - start)).runCollect
                  case _                                  => ZIO.succeed(Chunk.empty)
            yield Seen(kindOf(raw), raw.status, raw.header("x-h34s-stream"), chunks.map(_._1).toList, chunks.map(_._2).toList, headAt, producedAtHead)
          },
        )
        for seen <- ok[Seen](app, "/api/caller")
        yield assertTrue(
          seen.kind == "streamed",
          seen.marker.contains("1"),
          seen.texts.mkString == "1\n2\n3\n",
          seen.producedAtHead == 0,
          seen.headAt < seen.times.head,
          seen.times.last >= 550,
        )
      },
      // The ZIO guide's example (docs/content/1.guide/04.zio.md, "Call other routes with $fetch"): keep them equal.
      test("the guide's $fetch example: get decodes the envelope, and raw streams a body that the caller reads in part") {
        val app = NitroLocal.App()
        app.route("/api/greeting", ZHandler.define(_ => ZIO.succeed(Greeting("hi"))))
        app.route(
          "/api/events",
          ZHandler.define(_ => Response.header("content-type", "application/x-ndjson") *> Response.sendStream(ZStream("1\n", "2\n", "3\n"))),
        )
        // region guide-zio-fetch-call
        val summary: Request => ZIO[HandlerEnv, FetchError, String] = _ =>
          for
            greeting <- ZHandler.$fetch.get[GreetingData]("/api/greeting")
            events   <- ZHandler.$fetch.raw("/api/events")
            first    <- events match
              case FetchResponse.Streamed(_, _, body) => body.take(2).runCollect.map(_.mkString).orElseSucceed("the stream broke")
              case plain: FetchResponse.Plain         => ZIO.succeed(plain.text)
          yield s"${greeting.data.message}: $first"
        // endregion guide-zio-fetch-call
        app.route("/api/summary", ZHandler.define(summary))
        for data <- ok[String](app, "/api/summary")
        yield assertTrue(data == "hi: 1\n2\n")
      },
      test("a caller that drops a Streamed body: its Scope cancels the callee and waits for the callee's finalizers (Q16)") {
        var streamReleased  = 0
        var requestReleased = 0
        val app             = NitroLocal.App()
        val endless         = ZStream.unwrapScoped(
          ZIO.acquireRelease(ZIO.unit)(_ => ZIO.sleep(30.millis) *> ZIO.succeed { streamReleased += 1 }).as(ZStream("x") ++ ZStream.never),
        )
        app.route(
          "/api/endless",
          ZHandler.define(_ =>
            ZIO.acquireRelease(ZIO.unit)(_ => ZIO.sleep(30.millis) *> ZIO.succeed { requestReleased += 1 }) *> Response.sendStream(endless),
          ),
        )
        app.route("/api/caller", ZHandler.define(_ => ZHandler.$fetch.raw("/api/endless").map(raw => s"${kindOf(raw)} unread")))
        for data <- ok[String](app, "/api/caller")
        yield assertTrue(data == "streamed unread", streamReleased == 1, requestReleased == 1)
      },
      test("a caller that reads part of a Streamed body: the rest is cancelled when its Scope closes") {
        var streamReleased = 0
        val app            = NitroLocal.App()
        val endless        = ZStream.unwrapScoped(
          ZIO.acquireRelease(ZIO.unit)(_ => ZIO.succeed { streamReleased += 1 }).as(ZStream.repeat("x")),
        )
        app.route("/api/endless", ZHandler.define(_ => Response.sendStream(endless)))
        app.route(
          "/api/caller",
          ZHandler.define { _ =>
            ZHandler.$fetch.raw("/api/endless").flatMap {
              case FetchResponse.Streamed(_, _, body) => body.take(3).runCollect.map(_.mkString)
              case other                              => ZIO.succeed(s"unexpected ${kindOf(other)}")
            }
          },
        )
        for data <- ok[String](app, "/api/caller")
        yield assertTrue(data == "xxx", streamReleased == 1)
      },
      test("credentials: an in-process path gets the incoming cookie and authorization; an absolute URL gets only the caller's headers") {
        final case class Forwarded(cookie: Option[String], bearer: Option[String], caller: Option[String]) derives NativeConverter
        final case class ForwardedData(data: Forwarded) derives NativeConverter
        final case class Pair(local: Forwarded, network: Forwarded) derives NativeConverter
        val app = NitroLocal.App()
        app.route(
          "/api/headers",
          ZHandler.define(request => ZIO.succeed(Forwarded(request.header("cookie"), request.bearerToken, request.header("x-caller")))),
        )
        val outer = js.Dynamic.literal(headers = js.Dynamic.literal("cookie" -> "session=abc", "authorization" -> "Bearer t0k3n"))
        ZIO.fromFuture(_ => app.listen()).flatMap { server =>
          app.route(
            "/api/caller",
            ZHandler.define { _ =>
              for
                local <- ZHandler.$fetch.get[ForwardedData]("/api/headers")
                network <- ZHandler.$fetch.get[ForwardedData](server.url("/api/headers"), headers = Seq("x-caller" -> "1"))
              yield Pair(local.data, network.data)
            },
          )
          closing(server)(ok[Pair](app, "/api/caller", outer))
        }.map { data =>
          assertTrue(
            data.local == Forwarded(Some("session=abc"), Some("t0k3n"), None),
            data.network == Forwarded(None, None, Some("1")),
          )
        }
      },
      test("app.baseURL: an in-process path reaches a route under a non-root base; a path that carries the base is not prefixed again") {
        val app = NitroLocal.App(baseURL = "/app/")
        app.route("/api/greeting", ZHandler.define(_ => ZIO.succeed(Greeting("hi"))))
        app.route(
          "/api/caller",
          ZHandler.define { _ =>
            for
              short <- ZHandler.$fetch.get[GreetingData]("/api/greeting")
              full <- ZIO.scoped(ZHandler.$fetch.raw("/app/api/greeting"))
            yield s"${short.data.message} ${full.status}"
          },
        )
        val withBase = ZIO.acquireReleaseWith(ZIO.succeed(NitroLocal.installBaseURL("/app/")))(_ => ZIO.succeed(NitroGlobal.remove()))
        for data <- withBase(_ => ok[String](app, "/app/api/caller"))
        yield assertTrue(data == "hi 200")
      },
      test("an in-process call passes only the caller's _platform and waitUntil: the callee cannot read the caller's other context entries") {
        final case class Context(unenv: List[String], context: List[String], platform: String, waitUntil: String, session: Option[String])
            derives NativeConverter
        final case class ContextData(data: Context) derives NativeConverter
        val app = NitroLocal.App()
        app.onRequest(event => if event.path.asInstanceOf[String] == "/api/caller" then event.context.session = "secret-session")
        app.routeNative(
          "/api/context",
          event => {
            val unenv = event.node.req.__unenv__
            val keys  = (value: js.Dynamic) => js.Object.keys(value.asInstanceOf[js.Object]).toList.sorted
            js.Dynamic.literal(data =
              js.Dynamic.literal(
                unenv = js.Array(keys(unenv)*),
                context = js.Array(keys(event.context)*),
                platform = event.context.edge,
                waitUntil = js.typeOf(event.context.waitUntil),
                session = if js.isUndefined(unenv.session) then event.context.session else unenv.session,
              ),
            )
          },
        )
        app.route("/api/caller", ZHandler.define(_ => ZHandler.$fetch.get[ContextData]("/api/context").map(_.data)))
        val waitUntil: js.Function1[js.Any, Unit] = _ => ()
        val outer = js.Dynamic.literal(context = js.Dynamic.literal(_platform = js.Dynamic.literal(edge = "edge-1"), waitUntil = waitUntil))
        for seen <- ok[Context](app, "/api/caller", outer)
        yield assertTrue(
          seen.unenv == List("_platform", "waitUntil"),
          !seen.context.contains("session"),
          seen.session.isEmpty,
          seen.platform == "edge-1",
          seen.waitUntil == "function",
        )
      },
      test("a callee stream that fails after its head: the ZStream fails with a typed FetchError.BodyFailed; text fails the same way") {
        val app     = NitroLocal.App()
        val failing = ZStream("one\n") ++ ZStream.fail(Exception("private detail of the callee"))
        app.route("/api/failing", ZHandler.define(_ => Response.status(Status.Accepted) *> Response.sendStream(failing)))
        def outcome(result: Either[FetchError, Any]): String = result match
            case Left(FetchError.BodyFailed(status, reason)) => s"BodyFailed($status, $reason)"
            case Left(other)                                 => other.toString
            case Right(_)                                    => "ended"
        app.route(
          "/api/caller",
          ZHandler.define { _ =>
            for
              seen <- Ref.make(Vector.empty[String])
              raw <- ZHandler.$fetch.raw("/api/failing")
              read <- raw match
                  case FetchResponse.Streamed(status, _, body) =>
                    body.tap(text => seen.update(_ :+ text)).runDrain.either.flatMap(result =>
                      seen.get.map(texts => s"$status ${texts.mkString.trim} ${outcome(result)}"),
                    )
                  case other => ZIO.succeed(s"unexpected ${kindOf(other)}")
              text <- ZHandler.$fetch.text("/api/failing").either
            yield s"$read | ${outcome(text)}"
          },
        )
        val reason = "the body ended with an error before its end"
        for data <- ok[String](app, "/api/caller")
        yield assertTrue(
          data == s"202 one BodyFailed(202, $reason) | BodyFailed(202, $reason)",
          !data.contains("private detail"),
        )
      },
      test("headerValues keeps each set-cookie of the callee apart, in-process and over HTTP; headers joins them") {
        final case class Cookies(values: List[String], joined: String, tags: List[String]) derives NativeConverter
        final case class Pair(local: Cookies, network: Cookies) derives NativeConverter
        def cookies(response: FetchResponse): Cookies =
          Cookies(response.headerValues("Set-Cookie"), response.header("set-cookie").getOrElse(""), response.headerValues("x-tag"))
        val app = NitroLocal.App()
        app.route(
          "/api/cookies",
          ZHandler.define { _ =>
            Response.cookie(SetCookie("a", "1")) *> Response.cookie(SetCookie("b", "2", maxAge = Some(60))) *>
              Response.appendHeader("x-tag", "one") *> Response.appendHeader("x-tag", "two")
          },
        )
        ZIO.fromFuture(_ => app.listen()).flatMap { server =>
          app.route(
            "/api/caller",
            ZHandler.define { _ =>
              for
                local <- ZHandler.$fetch.raw("/api/cookies")
                network <- ZHandler.$fetch.raw(server.url("/api/cookies"))
              yield Pair(cookies(local), cookies(network))
            },
          )
          closing(server)(ok[Pair](app, "/api/caller"))
        }.map { data =>
          assertTrue(
            List(data.local, data.network).forall(seen =>
              seen.values.size == 2 && seen.values.head.startsWith("a=1;") && seen.values(1).startsWith("b=2;") &&
                seen.joined == seen.values.mkString(", ") && seen.tags == List("one, two"),
            ),
          )
        }
      },
      test("get[A] decodes the JSON as it arrives: a model of the { data: ... } envelope sees the value, a bare model does not") {
        final case class MaybeGreeting(message: Option[String]) derives NativeConverter
        val app = NitroLocal.App()
        app.route("/api/greeting", ZHandler.define(_ => ZIO.succeed(Greeting("hi"))))
        app.route(
          "/api/caller",
          ZHandler.define { _ =>
            for
              enveloped <- ZHandler.$fetch.get[GreetingData]("/api/greeting")
              bare <- ZHandler.$fetch.get[MaybeGreeting]("/api/greeting")
            yield s"${enveloped.data.message} ${bare.message}"
          },
        )
        for data <- ok[String](app, "/api/caller")
        yield assertTrue(data == "hi None")
      },
      test("post[A] sends JSON with its own content type; a callee error status is FetchError.Status for text and a plain status for raw") {
        val app = NitroLocal.App()
        app.route(
          "/api/echo",
          ZHandler.define(request => request.body.json[Input].map(input => Greeting(s"Hello, ${input.name}"))),
        )
        app.route("/api/missing", ZHandler.define(_ => ZHandler.failWith(404, "No such note").as(())))
        app.route(
          "/api/caller",
          ZHandler.define { _ =>
            for
              posted <- ZHandler.$fetch.post[GreetingData]("/api/echo", FetchBody.json(Input("Scala")))
              raw <- ZIO.scoped(ZHandler.$fetch.raw("/api/missing"))
              text <- ZHandler.$fetch.text("/api/missing").either
            yield s"${posted.data.message} ${raw.status} ${text match
                case Left(FetchError.Status(status, body)) => s"Status($status, $body)"
                case other                                 => other.toString
              }"
          },
        )
        for data <- ok[String](app, "/api/caller")
        yield assertTrue(data == "Hello, Scala 404 Status(404, {\"errors\":[{\"message\":\"No such note\"}]})")
      },
      test("an absolute URL that cannot be reached is a typed FetchError.Failed") {
        val app = NitroLocal.App()
        for
          server <- ZIO.fromFuture(_ => app.listen())
          closed = server.url("/api/gone")
          _ <- ZIO.fromFuture(_ => server.close())
          _ = app.route(
            "/api/caller",
            ZHandler.define(_ =>
              ZIO.scoped(ZHandler.$fetch.raw(closed)).either.map {
                case Left(FetchError.Failed(_)) => "failed"
                case other                      => other.toString
              },
            ),
          )
          data <- ok[String](app, "/api/caller")
        yield assertTrue(data == "failed")
      },
      // claude 2026-10-20: created: a ZIO callee's sendEvents read by $fetch.events[E] in-process, head-first (ADR-0007)
      test("sendEvents in-process: $fetch.events[E] returns head-first and decodes the typed events as they arrive") {
        var produced = 0
        val app      = NitroLocal.App()
        val ticks    = ZStream(3, 2, 1).mapZIO(remaining =>
          ZIO.sleep(200.millis) *> ZIO.succeed { produced += 1; ServerEvent.json(Tick(remaining), event = Some("tick")) },
        )
        app.route("/api/ticks", ZHandler.define(_ => Response.sendEvents(ticks)))
        app.route(
          "/api/caller",
          ZHandler.define { _ =>
            val start = js.Date.now()
            for
              events <- ZHandler.$fetch.events[Tick]("/api/ticks")
              headAt = js.Date.now() - start
              producedAtHead = produced
              seen <- events.map(tick => (tick.remaining, js.Date.now() - start)).runCollect
            yield TicksSeen(seen.map(_._1).toList, seen.map(_._2).toList, headAt, producedAtHead)
          },
        )
        for seen <- ok[TicksSeen](app, "/api/caller")
        yield assertTrue(
          seen.remaining == List(3, 2, 1),
          seen.producedAtHead == 0,
          seen.headAt < seen.times.head,
          seen.times.last >= 550,
        )
      },
      // claude 2026-10-20: created: a JavaScript route with h3's createEventStream read by the untyped $fetch.events (ADR-0007; the content-type path of ADR-0005, Q2)
      test("a JavaScript route with h3's createEventStream: the untyped $fetch.events reads its events by the content type") {
        val app = NitroLocal.App()
        app.routeEventStream("/api/js-clock", js.Array(js.Dynamic.literal(event = "tick", id = "1", data = "one"), "two"))
        // The route sets no x-h34s-stream marker: only its content type makes it an event stream. The
        // test reads the body to its end: a cancel of h3's createEventStream body leaks unhandled
        // rejections in-process (ADR-0007).
        app.route(
          "/api/caller",
          ZHandler.define(_ => ZHandler.$fetch.events(FetchRequest("/api/js-clock")).flatMap(_.runCollect).map(_.map(shown).mkString(" ; "))),
        )
        for data <- ok[String](app, "/api/caller")
        yield assertTrue(data == "tick|1|one ; message|-|two")
      },
      // claude 2026-10-20: created: events refuses a response that is not an event stream with a typed FetchError (ADR-0007)
      test("events of a route that is not an event stream fails with FetchError.NotEvents; an error status with FetchError.Status") {
        val app = NitroLocal.App()
        app.route("/api/greeting", ZHandler.define(_ => ZIO.succeed(Greeting("hi"))))
        app.route("/api/missing", ZHandler.define(_ => ZHandler.failWith(404, "No such stream").as(())))
        app.route(
          "/api/caller",
          ZHandler.define { _ =>
            for
              json <- ZHandler.$fetch.events[Tick]("/api/greeting").either
              missing <- ZHandler.$fetch.events(FetchRequest("/api/missing")).either
            yield s"${failure(json)} | ${failure(missing)}"
          },
        )
        for data <- ok[String](app, "/api/caller")
        yield assertTrue(data == "NotEvents(200, application/json) | Status(404, {\"errors\":[{\"message\":\"No such stream\"}]})")
      },
      // claude 2026-10-20: created: an event whose data is not JSON for E fails the stream with FetchError.Decode, after the events before it (ADR-0007)
      test("a typed event whose data is not JSON for E fails the stream with FetchError.Decode, which names the event") {
        val app = NitroLocal.App()
        app.route(
          "/api/mixed",
          ZHandler.define(_ => Response.sendEvents(ZStream(ServerEvent.json(Tick(3)), ServerEvent("not json", event = Some("oops")), ServerEvent.json(Tick(1))))),
        )
        app.route(
          "/api/caller",
          ZHandler.define { _ =>
            for
              seen <- Ref.make(Vector.empty[Int])
              events <- ZHandler.$fetch.events[Tick]("/api/mixed")
              result <- events.foreach(tick => seen.update(_ :+ tick.remaining)).either
              values <- seen.get
            yield s"${values.mkString(",")} ${failure(result)}"
          },
        )
        for data <- ok[String](app, "/api/caller")
        yield assertTrue(data == "3 Decode(200, the data of event 2 (\"oops\") is not valid JSON)")
      },
      // claude 2026-10-20: created: frames split across chunks over real HTTP decode the same; events sends accept: text/event-stream (ADR-0007)
      test("over real HTTP (absolute URL), events split inside lines and inside a CRLF decode to the same typed events") {
        val app   = NitroLocal.App()
        val split = ZStream("data: {\"remain", "ing\":2}\r", "\n\r\nda", "ta: {\"remaining\":1}\n", "\n", ": done\n")
        app.route(
          "/api/split",
          ZHandler.define { request =>
            if request.header("accept").contains("text/event-stream") then
                Response.header("content-type", "text/event-stream") *> Response.sendStream(split)
            else ZHandler.failWith(406, "events only")
          },
        )
        for
          server <- ZIO.fromFuture(_ => app.listen())
          _ = app.route(
            "/api/caller",
            ZHandler.define(_ => ZHandler.$fetch.events[Tick](server.url("/api/split")).flatMap(_.runCollect).map(_.map(_.remaining).mkString(","))),
          )
          data <- closing(server)(ok[String](app, "/api/caller"))
        yield assertTrue(data == "2,1")
      },
      // Sequential: the base URL test installs the plugin's startup record, a global that every
      // in-process call of the other tests reads.
    ) @@ TestAspect.sequential @@ TestAspect.withLiveClock @@ TestAspect.timeout(60.seconds)
end NitroLocalFetchSpec
