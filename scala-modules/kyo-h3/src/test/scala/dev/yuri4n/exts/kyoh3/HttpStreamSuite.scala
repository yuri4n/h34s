package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.kyoh3.KyoTesting.*
import kyo.*
import munit.FunSuite
import org.getshaka.nativeconverter.NativeConverter

import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js

// claude 2026-10-19: changed: every handler streams with Response.sendStream and returns its Sent (decision 1)
/** Streamed response bodies through h3's `sendStream` (the ZIO Z13-Z18 contracts, ported). */
class HttpStreamSuite extends FunSuite:
    given ExecutionContext = ExecutionContext.global
    override val munitTimeout: FiniteDuration = 60.seconds

    // Z13, C12
    // claude 2026-10-20: changed: the head is written and flushed at the call, before the first element (ADR-0004, Q1)
    test("writes and flushes the head at the call, before the first element, then every chunk, and ends the response") {
      val call = Call("/stream")
      var flushedBeforeProduction: Option[(Int, List[String], Int)] = None
      val stream = Stream[String, Async] {
        Sync.defer { flushedBeforeProduction = call.response.headAtFlush }.andThen(Emit.value(Chunk("a", "b", "c")))
      }
      val handler = KHandler.define { _ =>
        Response.status(Status.Accepted)
          .andThen(Response.header("content-type", "text/plain"))
          .andThen(Response.sendStream(stream))
      }
      call.invoke(handler).map { body =>
        assert(js.isUndefined(body))
        // claude 2026-10-19: changed: sendStream adds the x-h34s-stream marker to the head
        assertEquals(call.response.headAtFlush, Some((202, List("content-type", "x-h34s-stream"), 0)))
        assertEquals(flushedBeforeProduction, call.response.headAtFlush, "the head left before the first element was produced")
        assertEquals(call.response.chunks.toList, List("a", "b", "c"))
        assert(call.response.ended)
        assert(!call.response.hasListener)
        assert(call.handled)
      }
    }

    // claude 2026-10-20: created: the streaming guide's Kyo example (docs/content/1.guide/09.streaming.md); keep them equal
    test("the guide's example sends abc with status 202") {
      val call = Call("/stream")
      val letters: EventHandler = KHandler.define: _ =>
          Response.status(Status.Accepted)
            .andThen(Response.header("content-type", "text/plain"))
            .andThen(Response.sendStream(Stream.init(Seq("a", "b", "c"))))
      call.invoke(letters).map { _ =>
        assertEquals(call.response.status, 202)
        assertEquals(call.response.chunks.mkString, "abc")
      }
    }

    test("an empty stream still writes the head and ends the response") {
      val call = Call("/stream")
      call.invoke(KHandler.define(_ => Response.status(Status.NoContent).andThen(Response.sendStream(Stream.empty[String])))).map { _ =>
        assertEquals(call.response.status, 204)
        assert(call.response.ended)
        assertEquals(call.response.chunks.toList, Nil)
        assertEquals(call.response.header("content-type"), Some("text/plain; charset=utf-8"))
        assertEquals(call.response.header("x-content-type-options"), Some("nosniff"))
      }
    }

    // Z14, C7
    test("disconnect interrupts the stream and releases stream and request resources once") {
      var released = 0
      var streamReleased = 0
      val call = Call("/stream")
      val stream = Stream[String, Async & Scope] {
        Scope.acquireRelease(Sync.defer(()))(_ => Sync.defer { streamReleased += 1 })
          .andThen(Emit.value(Chunk("first")))
          .andThen(Async.never[Unit])
      }
      val handler = KHandler.define { _ =>
        Scope.acquireRelease(Sync.defer(()))(_ => Sync.defer { released += 1 }).andThen(Response.sendStream(stream))
      }
      val running = call.invoke(handler)
      val checked = for
        _ <- call.response.firstWrite.future
        _ = call.response.disconnect()
        body <- running
      yield
        assert(js.isUndefined(body))
        assertEquals(released, 1)
        assertEquals(streamReleased, 1)
        assertEquals(call.response.chunks.toList, List("first"))
        assert(!call.response.hasListener)
      TestDeadline(checked)
    }

    // Z15
    test("a response closed before the stream starts completes without starting it") {
      var started = false
      val call = Call("/stream")
      call.response.disconnect()
      call.invoke(KHandler.define(_ => Sync.defer { started = true }.andThen(Response.sendStream(Stream.init(Seq("never")))))).map { body =>
        assert(js.isUndefined(body))
        assert(!started)
        assert(!call.response.hasListener)
      }
    }

    // Z16
    test("interruption before the first write joins the stream cleanup") {
      val acquired = Promise[Unit]()
      var released = 0
      val call = Call("/stream")
      val stream = Stream[String, Async & Scope] {
        Scope.acquireRelease(Sync.defer { acquired.trySuccess(()); () })(_ => Sync.defer { released += 1 })
          .andThen(Async.never[Unit])
      }
      val running = call.invoke(KHandler.define(_ => Response.sendStream(stream)))
      val checked = for
        _ <- acquired.future
        _ = call.response.disconnect()
        _ <- running
      yield
        assertEquals(released, 1)
        assertEquals(call.response.chunks.toList, Nil)
        assert(!call.response.hasListener)
      TestDeadline(checked)
    }

    // Z17
    test("a failure after the first chunk destroys the socket and keeps written chunks") {
      val call = Call("/stream")
      val stream = Stream.init(Seq("prefix")).concat(Stream[String, Abort[Exception]](Abort.fail(new Exception("private details"))))
      call.invoke(KHandler.define(_ => Response.sendStream(stream))).map { body =>
        assert(js.isUndefined(body))
        assert(call.response.destroyed)
        assertEquals(call.response.chunks.toList, List("prefix"))
        assert(!call.response.hasListener)
      }
    }

    // claude 2026-10-20: changed: the head leaves at the call, so a failure before the first element can only end the stream (ADR-0004, Q1)
    test("a failure before the first element ends the stream: the status stays and the connection is closed") {
      val call = Call("/stream")
      val stream = Stream[String, Abort[HttpError]](Abort.fail(HttpError.conflict("Busy")))
      val handler = KHandler.define { _ =>
        Response.status(Status.Accepted).andThen(Response.header("x-sent", "1")).andThen(Response.sendStream(stream))
      }
      consoleErrors(call.invoke(handler)).map { (body, logged) =>
        assert(js.isUndefined(body))
        assertEquals(call.response.status, 202)
        assertEquals(call.response.header("x-sent"), Some("1"))
        assertEquals(call.response.chunks.toList, Nil)
        assert(call.response.destroyed, "the connection is closed")
        assert(!call.response.ended, "the response is not ended like a complete one")
        assert(logged.exists(_.contains("Busy")), logged.toString)
      }
    }

    // claude 2026-10-20: changed: the head leaves at the call; the client sees no error data, only an ended connection (ADR-0004, Q1)
    test("a SafeError thrown inside a stream never reaches the client; a null element ends the stream too") {
      final case class Approved(message: String) extends SafeError:
        override def status: Int = 409
      val thrown = Call("/stream")
      val nullElement = Call("/stream")
      for
        thrownBody <- thrown.invoke(KHandler.define(_ => Response.sendStream(Stream.init(Seq("x")).map(_ => (throw Approved("private detail")): String))))
        nullBody <- nullElement.invoke(KHandler.define(_ => Response.sendStream(Stream.init(Seq[String](null)))))
      yield
        assert(js.isUndefined(thrownBody))
        assertEquals(thrown.response.status, 200)
        assertEquals(thrown.response.chunks.toList, Nil)
        assert(thrown.response.destroyed)
        assert(js.isUndefined(nullBody))
        assertEquals(nullElement.response.chunks.toList, Nil)
        assert(nullElement.response.destroyed)
    }

    // claude 2026-10-20: changed: the head leaves at the call, so a failed write closes the connection; the log names the write error (ADR-0004, Q1)
    test("a failed write closes the connection, and the log names the write error, not the producer's interruption") {
      val call = Call("/stream", development = true)
      call.response.native.updateDynamic("write")(((_: js.Any) =>
        throw js.JavaScriptException(js.Dynamic.newInstance(js.Dynamic.global.TypeError)("the response rejected the chunk"))
      ): js.Function1[js.Any, Boolean])
      val handler = KHandler.define { _ =>
        Response.status(Status.Accepted)
          .andThen(Response.cookie(SetCookie("session", "abc")))
          .andThen(Response.sendStream(Stream.init(Seq("a"))))
      }
      consoleErrors(call.invoke(handler)).map { (body, logged) =>
        assert(js.isUndefined(body))
        assertEquals(call.response.status, 202)
        assert(call.response.header("set-cookie").exists(_.startsWith("session=abc")))
        assert(call.response.destroyed)
        val failure = logged.filter(_.contains("Unhandled h34s handler failure"))
        assertEquals(failure.size, 1, logged.toString)
        assert(failure.head.contains("the response rejected the chunk"), failure.head)
        assert(!failure.head.contains("Interrupted"), failure.head)
      }
    }

    // claude 2026-10-20: created: a head that cannot be written fails the step before a producer exists; the failure rule answers (ADR-0004, Q1)
    test("a head that cannot be written fails the sendStream step before the stream starts, and the failure rule answers") {
      val call = Call("/stream")
      var started = false
      val stream = Stream[String, Async](Sync.defer { started = true }.andThen(Emit.value(Chunk("never"))))
      val handler = KHandler.define { _ =>
        Response.header("x-sent", "1")
          .andThen(Response.cookie(SetCookie("session", "abc", domain = Some("bad\ndomain"))))
          .andThen(Response.sendStream(stream))
      }
      consoleErrors(call.invoke(handler)).map { (body, logged) =>
        assertOpaque(body)
        assertEquals(call.response.status, 500)
        assertEquals(call.response.header("x-sent"), None)
        assertEquals(call.response.header("x-h34s-stream"), None)
        assertEquals(call.response.header("set-cookie"), None)
        assert(!started, "the stream never started")
        assert(!call.response.destroyed, "the connection stays usable for the error response")
        assertEquals(call.response.chunks.toList, Nil)
        assert(!call.response.hasListener)
        assert(logged.exists(_.contains("option domain is invalid")), logged.toString)
      }
    }

    // claude 2026-10-20: created: a head that fails after part of it left closes the connection; no producer is left (ADR-0004, Q1)
    test("a head that fails after part of it left closes the connection, and the stream never starts") {
      val call = Call("/stream")
      var started = false
      call.response.native.headersSent = true
      call.response.native.updateDynamic("setHeader")(((_: String, _: js.Any) =>
        throw js.JavaScriptException(js.Dynamic.newInstance(js.Dynamic.global.Error)("Cannot set headers after they are sent to the client"))
      ): js.Function2[String, js.Any, js.Dynamic])
      val stream = Stream[String, Async](Sync.defer { started = true }.andThen(Emit.value(Chunk("never"))))
      consoleErrors(call.invoke(KHandler.define(_ => Response.sendStream(stream)))).map { (body, logged) =>
        assert(js.isUndefined(body))
        assert(call.response.destroyed, "the connection is closed")
        assert(!call.response.ended, "the response is not ended like a complete one")
        assertEquals(call.response.writes, 0)
        assertEquals(call.response.chunks.toList, Nil)
        assert(!started, "the stream never started")
        assert(!call.response.hasListener)
        assert(call.handled)
        assertEquals(logged.count(_.contains("Cannot set headers after they are sent")), 1, logged.toString)
      }
    }

    // claude 2026-10-20: created: a stream failure after the head fails the sendStream step; a Scope around it sees the failure (ADR-0004, Q3)
    test("a stream failure after two elements fails the sendStream step; a Scope finalizer around it sees the failure") {
      val call = Call("/stream")
      val failure = new Exception("the third element failed")
      var seen: Option[String] = None
      var step: Option[Result[Throwable, Sent]] = None
      val stream = Stream.init(Seq("one", "two")).concat(Stream[String, Abort[Exception]](Abort.fail(failure)))
      val handler = KHandler.define { _ =>
        Abort.run[Throwable](
          Scope.run(
            Scope.ensure((outcome: Maybe[Result.Error[Any]]) => Sync.defer { seen = Some(outcome.fold("success")(_.toString)) })
              .andThen(Response.sendStream(stream)),
          ),
        ).map(result => Sync.defer { step = Some(result) }.andThen(Abort.get(result)))
      }
      consoleErrors(call.invoke(handler)).map { (body, logged) =>
        assert(js.isUndefined(body))
        assertEquals(step, Some(Result.fail(failure)))
        assert(seen.exists(_.contains("the third element failed")), seen.toString)
        assertEquals(call.response.chunks.toList, List("one", "two"))
        assert(call.response.destroyed, "the connection is closed")
        assert(!call.response.ended, "no final chunk: the response does not look complete")
        assertEquals(call.response.writes, 0)
        assertEquals(logged.count(_.contains("the third element failed")), 1, logged.toString)
      }
    }

    // claude 2026-10-20: created: in Kyo, only a scope inside the handler sees a stream failure; the request scope's finalizers get Absent (ADR-0004, Q3)
    test("after a stream failure, a finalizer of the request scope gets Absent, and one of a Scope.run around sendStream gets the failure") {
      val call = Call("/stream")
      val failure = new Exception("the second element failed")
      var inRequestScope: Option[String] = None
      var inInnerScope: Option[String] = None
      val stream = Stream.init(Seq("one")).concat(Stream[String, Abort[Exception]](Abort.fail(failure)))
      val handler = KHandler.define { _ =>
        Scope.ensure((outcome: Maybe[Result.Error[Any]]) => Sync.defer { inRequestScope = Some(outcome.fold("Absent")(_.toString)) })
          .andThen(
            Scope.run(
              Scope.ensure((outcome: Maybe[Result.Error[Any]]) => Sync.defer { inInnerScope = Some(outcome.fold("Absent")(_.toString)) })
                .andThen(Response.sendStream(stream)),
            ),
          )
      }
      consoleErrors(call.invoke(handler)).map { (body, _) =>
        assert(js.isUndefined(body))
        assertEquals(call.response.chunks.toList, List("one"))
        assert(inInnerScope.exists(_.contains("the second element failed")), inInnerScope.toString)
        assertEquals(inRequestScope, Some("Absent"), "h34s closes the request scope after the program, without its outcome")
      }
    }

    // claude 2026-10-20: created: a Scope.run around sendStream keeps the disconnect order (ADR-0004, Q3 guidance)
    test("a disconnect waits for the asynchronous finalizers of a Scope.run around sendStream before the request settles") {
      var events = Vector.empty[String]
      val call = Call("/stream")
      val stream = Stream[String, Async & Scope] {
        Scope.ensure(Async.sleep(30.millis).andThen(Sync.defer { events = events :+ "stream released" }))
          .andThen(Emit.value(Chunk("first")))
          .andThen(Async.never[Unit])
      }
      val handler = KHandler.define { _ =>
        Scope.run(
          Scope.ensure(Async.sleep(30.millis).andThen(Sync.defer { events = events :+ "inner scope released" }))
            .andThen(Response.sendStream(stream)),
        )
      }
      val running = call.invoke(handler)
      val checked = for
        _ <- call.response.firstWrite.future
        _ = call.response.disconnect()
        body <- running
      yield
        assert(js.isUndefined(body))
        assertEquals(events, Vector("stream released", "inner scope released"))
      TestDeadline(checked)
    }

    // claude 2026-10-20: created: a stream failure on the socketless path errors the caller's reads and fails the step (ADR-0004, Q3)
    // claude 2026-10-20: changed: waits until the step and the log happen, not a fixed three turns (they lagged under load)
    test("without a Node socket, a stream failure after the head errors the caller's reads and fails the sendStream step") {
      val call = Call("/stream", withSocket = false)
      val failure = new Exception("the second element failed")
      var step: Option[Result[Throwable, Sent]] = None
      val stream = Stream.init(Seq("one")).concat(Stream[String, Abort[Exception]](Abort.fail(failure)))
      val handler = KHandler.define { _ =>
        Abort.run[Throwable](Response.sendStream(stream)).map(result => Sync.defer { step = Some(result) }.andThen(Abort.get(result)))
      }
      def checked(logged: () => List[String]) =
        for
          body <- call.invoke(handler)
          reader = call.response.native._data.getReader()
          first <- reader.read().asInstanceOf[js.Promise[js.Dynamic]].toFuture
          second <- reader.read().asInstanceOf[js.Promise[js.Dynamic]].toFuture.transform(outcome => scala.util.Success(outcome))
          _ <- turnsUntil(1000)(step.isDefined && logged().exists(_.contains("the second element failed")))
          _ <- turns(3) // a second log line would arrive within these turns
        yield
          assert(js.isUndefined(body))
          assertEquals(decode(first.value), "one")
          assert(second.isFailure, s"the second read must fail, got $second")
          assertEquals(step, Some(Result.fail(failure)))
      TestDeadline(consoleErrorsSeen(checked).map { (_, logged) =>
        assertEquals(logged.count(_.contains("the second element failed")), 1, logged.toString)
      })
    }

    // Z18
    // claude 2026-10-19: changed: a socketless response (Nitro local fetch) is no longer rejected: the head is written first, h3 keeps the stream in res._data, the h3 promise settles at once, and the request scope stays open until the stream is read or cancelled
    // claude 2026-10-20: changed: waits until the request scope is released, not a fixed three turns
    test("without a Node socket, the head is written first and the stream is handed over unread, as UTF-8 bytes") {
      var acquired = 0
      var released = 0
      var requestReleased = 0
      val call = Call("/stream", withSocket = false)
      val stream = Stream[String, Async & Scope] {
        Scope.acquireRelease(Sync.defer { acquired += 1 })(_ => Sync.defer { released += 1 })
          .andThen(Emit.value(Chunk("da", "ta")))
      }
      val handler = KHandler.define { _ =>
        Scope.ensure(Sync.defer { requestReleased += 1 })
          .andThen(Response.status(Status.Accepted))
          .andThen(Response.sendStream(stream))
      }
      val checked =
        for
          body <- call.invoke(handler)
          // Settled before any chunk: nothing produced, the request scope still open.
          _ = assert(js.isUndefined(body))
          _ = assertEquals((acquired, released, requestReleased), (0, 0, 0))
          _ = assertEquals(call.response.status, 202)
          _ = assertEquals(call.response.header("x-h34s-stream"), Some("1"))
          _ = assertEquals(call.response.header("content-type"), Some("text/plain; charset=utf-8"))
          _ = assert(call.handled)
          text <- readText(call.response.native._data)
          _ <- turnsUntil(1000)(requestReleased > 0)
          _ <- turns(3) // a second release would happen within these turns
        yield
          assertEquals(text, "data")
          assertEquals((acquired, released, requestReleased), (1, 1, 1))
          assertEquals(call.response.chunks.toList, Nil)
      TestDeadline(checked)
    }

    // claude 2026-10-19: created: a socketless cancel waits for the stream's and the request's finalizers
    test("without a Node socket, cancelling the handed-over stream runs stream and request finalizers before the cancel resolves") {
      var released = 0
      var requestReleased = 0
      val call = Call("/stream", withSocket = false)
      val stream = Stream[String, Async & Scope] {
        Scope.acquireRelease(Sync.defer(()))(_ => Async.sleep(50.millis).andThen(Sync.defer { released += 1 }))
          .andThen(Emit.value(Chunk("first")))
          .andThen(Async.never[Unit])
      }
      val handler = KHandler.define { _ =>
        Scope.ensure(Async.sleep(50.millis).andThen(Sync.defer { requestReleased += 1 })).andThen(Response.sendStream(stream))
      }
      val checked =
        for
          _ <- call.invoke(handler)
          reader = call.response.native._data.getReader()
          first <- reader.read().asInstanceOf[js.Promise[js.Dynamic]].toFuture
          _ <- reader.cancel("caller scope closed").asInstanceOf[js.Promise[Unit]].toFuture
        yield
          assertEquals(decode(first.value), "first")
          assertEquals((released, requestReleased), (1, 1))
      TestDeadline(checked)
    }

    // claude 2026-10-19: created: sendStream is the handler's result; a second call is a defect
    test("calling sendStream twice is a defect; the first stream is the response") {
      val call = Call("/stream")
      val handler = KHandler.define(_ =>
        Response.sendStream(Stream.init(Seq("one"))).andThen(Response.sendStream(Stream.init(Seq("two")))),
      )
      call.invoke(handler).map { body =>
        assert(js.isUndefined(body))
        assertEquals(call.response.chunks.toList, List("one"))
        assert(call.response.ended)
      }
    }

    // claude 2026-10-19: created: a value returned after sendStream is not written
    test("a value returned after sendStream writes nothing more") {
      val call = Call("/stream")
      val handler = KHandler.define(_ => Response.sendStream(Stream.init(Seq("only"))).map(_ => "ignored"))
      call.invoke(handler).map { body =>
        assert(js.isUndefined(body))
        assertEquals(call.response.chunks.toList, List("only"))
        assertEquals(call.response.header("content-type"), Some("text/plain; charset=utf-8"))
      }
    }

    // claude 2026-10-20: created: the head leaves at the call, so a change after it is not on the wire (ADR-0004, Q1)
    test("a status, header or cookie set after sendStream is not on the wire") {
      val call = Call("/stream")
      val handler = KHandler.define { _ =>
        Response.status(Status.Accepted).andThen(Response.sendStream(Stream.init(Seq("a")))).map { sent =>
          Response.status(Status.Created)
            .andThen(Response.header("x-late", "1"))
            .andThen(Response.cookie(SetCookie("late", "1")))
            .andThen(sent)
        }
      }
      call.invoke(handler).map { body =>
        assert(js.isUndefined(body))
        assertEquals(call.response.status, 202)
        assertEquals(call.response.header("x-late"), None)
        assertEquals(call.response.header("set-cookie"), None)
        assertEquals(call.response.chunks.toList, List("a"))
        assert(call.response.ended)
      }
    }

    // claude 2026-10-19: created: outside KHandler.define, sendStream fails instead of writing
    test("sendStream on a detached transport fails with IllegalStateException") {
      run(Scope.run(Var.run(ResponseHead.empty)(detached(Response.sendStream(Stream.init(Seq("x"))))))).map { result =>
        assert(result.isPanic || result.isFailure, result.toString)
        assert(result.toString.contains("needs a request served by KHandler.define"), result.toString)
      }
    }

    // claude 2026-10-19: created: decision 1's negative compile check: a returned kyo.Stream is a compile error with h34s' message
    test("returning a kyo.Stream does not compile, and the message names sendStream") {
      val errors = compileErrors("KHandler.define(_ => kyo.Stream.init(Seq(\"a\")))")
      assert(errors.contains("h34s cannot send kyo.Stream[String, Any] as a response body"), errors)
      assert(errors.contains("Response.sendStream(stream)"), errors)
    }

    // ---- Server-sent events (ADR-0007) ----

    // claude 2026-10-20: created: a typed event for the sendEvents tests (ADR-0007)
    final case class Tick(remaining: Int) derives NativeConverter

    private val eventHeaders = Map(
      "content-type"      -> "text/event-stream",
      "cache-control"     -> "private, no-cache, no-store, no-transform, must-revalidate, max-age=0",
      "x-accel-buffering" -> "no",
      "connection"        -> "keep-alive",
    )

    // claude 2026-10-20: created: sendEvents sets h3's headers over the program's and the defaults' values, writes one frame per event and gives Sent (ADR-0007)
    test("sendEvents sets h3's event-stream headers, keeps the other head entries, writes one frame per event and gives Sent") {
      val call = Call("/events")
      var sent: Option[Sent] = None
      val events = Stream.init(Seq(ServerEvent.json(Tick(2), event = Some("tick"), id = Some("2")), ServerEvent("done")))
      val handler = KHandler.define(HandlerOptions(defaults = ResponseHead.noStore)) { _ =>
        Response.header("content-type", "text/plain")
          .andThen(Response.header("x-custom", "kept"))
          .andThen(Response.sendEvents(events))
          .map(value => Sync.defer { sent = Some(value); value })
      }
      call.invoke(handler).map { body =>
        assert(js.isUndefined(body))
        assertEquals(sent.map(_.toString), Some("Sent"))
        eventHeaders.foreach((name, value) => assertEquals(call.response.header(name), Some(value), name))
        assertEquals(call.response.header("x-custom"), Some("kept"))
        assertEquals(call.response.header("x-h34s-stream"), Some("1"))
        assertEquals(call.response.header("x-content-type-options"), None, "the content type is set, so no default is added")
        assertEquals(call.response.status, 200)
        assertEquals(call.response.chunks.toList, List("id: 2\nevent: tick\ndata: {\"remaining\":2}\n\n", "data: done\n\n"))
        assert(call.response.headAtFlush.exists(_._3 == 0), "the head left before the first frame")
        assert(call.response.ended)
      }
    }

    // claude 2026-10-20: created: no connection header over HTTP/2, as h3's createEventStream (ADR-0007)
    test("sendEvents over HTTP/2 leaves out the connection header, as h3's createEventStream does") {
      val call = Call("/events", headers = Seq(":method" -> "GET", ":path" -> "/events"))
      call.invoke(KHandler.define(_ => Response.sendEvents(Stream.init(Seq(ServerEvent("x")))))).map { _ =>
        assertEquals(call.response.header("content-type"), Some("text/event-stream"))
        assertEquals(call.response.header("connection"), None)
        assertEquals(call.response.chunks.toList, List("data: x\n\n"))
      }
    }

    // claude 2026-10-20: created: the cancel rule of sendStream holds for sendEvents (ADR-0007; the Z14 shape)
    test("a disconnect cancels the event stream and releases its resources and the request's once") {
      var released = 0
      var streamReleased = 0
      val call = Call("/events")
      val events = Stream[ServerEvent, Async & Scope] {
        Scope.acquireRelease(Sync.defer(()))(_ => Sync.defer { streamReleased += 1 })
          .andThen(Emit.value(Chunk(ServerEvent("first"))))
          .andThen(Async.never[Unit])
      }
      val handler = KHandler.define { _ =>
        Scope.acquireRelease(Sync.defer(()))(_ => Sync.defer { released += 1 }).andThen(Response.sendEvents(events))
      }
      val running = call.invoke(handler)
      val checked = for
        _ <- call.response.firstWrite.future
        _ = call.response.disconnect()
        body <- running
      yield
        assert(js.isUndefined(body))
        assertEquals(released, 1)
        assertEquals(streamReleased, 1)
        assertEquals(call.response.chunks.toList, List("data: first\n\n"))
        assert(!call.response.hasListener)
      TestDeadline(checked)
    }

    // claude 2026-10-20: created: a returned stream of events is the same compile error, and the message names sendEvents (ADR-0007)
    test("returning a kyo.Stream of ServerEvent does not compile, and the message names sendEvents") {
      val errors = compileErrors("KHandler.define(_ => kyo.Stream.init(Seq(ServerEvent(\"a\"))))")
      assert(errors.contains("h34s cannot send kyo.Stream["), errors)
      assert(errors.contains("ServerEvent, Any] as a response body"), errors)
      assert(errors.contains("Response.sendEvents(events)"), errors)
    }

    private def decode(value: js.Any): String =
      js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)().decode(value).asInstanceOf[String]

    /** Reads a Web stream of UTF-8 chunks to its end. */
    private def readText(stream: js.Dynamic): Future[String] =
      val reader = stream.getReader()
      def loop(acc: String): Future[String] =
        reader.read().asInstanceOf[js.Promise[js.Dynamic]].toFuture.flatMap { step =>
          if step.done.asInstanceOf[Boolean] then Future.successful(acc) else loop(acc + decode(step.value))
        }
      loop("")
end HttpStreamSuite
