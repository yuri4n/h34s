package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.kyoh3.KyoTesting.*
import kyo.*
import munit.FunSuite

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

    // Z18
    // claude 2026-10-19: changed: a socketless response (Nitro local fetch) is no longer rejected: the head is written first, h3 keeps the stream in res._data, the h3 promise settles at once, and the request scope stays open until the stream is read or cancelled
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
          _ <- turns(3)
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
