package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.kyoh3.KyoTesting.*
import kyo.*
import munit.FunSuite

import scala.concurrent.{ExecutionContext, Promise}
import scala.concurrent.duration.*
import scala.scalajs.js

/** Streamed response bodies through h3's `sendStream` (the ZIO Z13-Z18 contracts, ported). */
class HttpStreamSuite extends FunSuite:
    given ExecutionContext = ExecutionContext.global
    override val munitTimeout: FiniteDuration = 60.seconds

    // Z13, C12
    test("writes the head before the first chunk, then every chunk, and ends the response") {
      val call = Call("/stream")
      val handler = KHandler.define { _ =>
        Response.status(Status.Accepted)
          .andThen(Response.header("content-type", "text/plain"))
          .andThen(Stream.init(Seq("a", "b", "c")))
      }
      call.invoke(handler).map { body =>
        assert(js.isUndefined(body))
        assertEquals(call.response.headAtFirstWrite, Some(202 -> List("content-type")))
        assertEquals(call.response.chunks.toList, List("a", "b", "c"))
        assert(call.response.ended)
        assert(!call.response.hasListener)
        assert(call.handled)
      }
    }

    test("an empty stream still writes the head and ends the response") {
      val call = Call("/stream")
      call.invoke(KHandler.define(_ => Response.status(Status.NoContent).andThen(Stream.empty[String]))).map { _ =>
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
        Scope.acquireRelease(Sync.defer(()))(_ => Sync.defer { released += 1 }).andThen(stream)
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
      call.invoke(KHandler.define(_ => Sync.defer { started = true; Stream.init(Seq("never")) })).map { body =>
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
      val running = call.invoke(KHandler.define(_ => stream))
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
      call.invoke(KHandler.define(_ => stream)).map { body =>
        assert(js.isUndefined(body))
        assert(call.response.destroyed)
        assertEquals(call.response.chunks.toList, List("prefix"))
        assert(!call.response.hasListener)
      }
    }

    test("a failure before the first chunk is rendered with the failure rule") {
      val call = Call("/stream")
      val stream = Stream[String, Abort[HttpError]](Abort.fail(HttpError.conflict("Busy")))
      val handler = KHandler.define { _ =>
        Response.status(Status.Accepted).andThen(Response.header("x-discarded", "1")).andThen(stream)
      }
      call.invoke(handler).map { body =>
        assertEquals(call.response.status, 409)
        assertEquals(json(body), errors("Busy"))
        assertEquals(call.response.header("x-discarded"), None)
        assertEquals(call.response.chunks.toList, Nil)
      }
    }

    test("a SafeError thrown inside a stream is a defect, not an approved error; a null element too") {
      final case class Approved(message: String) extends SafeError:
        override def status: Int = 409
      val thrown = Call("/stream")
      val nullElement = Call("/stream")
      for
        thrownBody <- thrown.invoke(KHandler.define(_ => Stream.init(Seq("x")).map(_ => (throw Approved("private detail")): String)))
        nullBody <- nullElement.invoke(KHandler.define(_ => Stream.init(Seq[String](null))))
      yield
        assertEquals(thrown.response.status, 500)
        assertOpaque(thrownBody)
        assertEquals(thrown.response.chunks.toList, Nil)
        assertEquals(nullElement.response.status, 500)
        assertOpaque(nullBody)
    }

    test("a failed write before anything was sent is a 500 without the success head, with the write error") {
      val call = Call("/stream", development = true)
      call.response.native.updateDynamic("write")(((_: js.Any) =>
        throw js.JavaScriptException(js.Dynamic.newInstance(js.Dynamic.global.TypeError)("the response rejected the chunk"))
      ): js.Function1[js.Any, Boolean])
      val handler = KHandler.define { _ =>
        Response.status(Status.Accepted)
          .andThen(Response.cookie(SetCookie("session", "abc")))
          .andThen(Response.header("x-success", "1"))
          .andThen(Stream.init(Seq("a")))
      }
      call.invoke(handler).map { body =>
        assertEquals(call.response.status, 500)
        assertEquals(call.response.header("set-cookie"), None)
        assertEquals(call.response.header("x-success"), None)
        val cause = body.asInstanceOf[js.Dynamic].errors.asInstanceOf[js.Array[js.Dynamic]](0).debug.cause.asInstanceOf[String]
        assert(cause.contains("the response rejected the chunk"), cause)
        assert(!cause.contains("Interrupted"), cause)
      }
    }

    // Z18
    test("without a Node socket, streaming fails before acquiring stream resources") {
      var acquired = 0
      val call = Call("/stream", withSocket = false)
      val stream = Stream[String, Async & Scope] {
        Scope.acquireRelease(Sync.defer { acquired += 1 })(_ => Sync.defer(())).andThen(Emit.value(Chunk("data")))
      }
      call.invoke(KHandler.define(_ => stream)).map { body =>
        assertEquals(acquired, 0)
        assertEquals(call.response.status, 500)
        assertOpaque(body)
      }
    }
end HttpStreamSuite
