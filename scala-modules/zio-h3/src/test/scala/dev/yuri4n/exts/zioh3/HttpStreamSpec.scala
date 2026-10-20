package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.zioh3.ZioTesting.*
import zio.*
import zio.stream.ZStream
import zio.test.*

import scala.scalajs.js

// claude 2026-10-19: changed: every handler streams with ZHandler.sendStream and returns its Sent (decision 1)
/** ZStream bodies through the core's h3 `sendStream` path (Z13-Z18). */
object HttpStreamSpec extends ZIOSpecDefault:
    def spec = suite("ZStream response bodies over h3")(
      // Z13, C12
      // claude 2026-10-20: changed: the head is written and flushed at the call, before the first element (ADR-0004, Q1)
      test("writes and flushes the head at the call, before the first element, then every chunk, and ends the response") {
        val call = Call("/stream")
        var flushedBeforeProduction: Option[(Int, List[String], Int)] = None
        val stream = ZStream.fromZIO(ZIO.succeed { flushedBeforeProduction = call.response.headAtFlush; "a" }) ++ ZStream("b", "c")
        val handler = ZHandler.define { _ =>
          Response.status(Status.Accepted) *> Response.header("content-type", "text/plain") *> ZHandler.sendStream(stream)
        }
        for body <- call.invoke(handler)
        yield assertTrue(
          js.isUndefined(body),
          // claude 2026-10-19: changed: the core's send adds the x-h34s-stream marker to the head
          call.response.headAtFlush.contains((202, List("content-type", "x-h34s-stream"), 0)),
          flushedBeforeProduction == call.response.headAtFlush,
          call.response.chunks.toList == List("a", "b", "c"),
          call.response.ended, !call.response.hasListener, call.handled,
        )
      },
      test("an empty stream still writes the head and ends the response") {
        val call = Call("/stream")
        for _ <- call.invoke(ZHandler.define(_ => Response.status(Status.NoContent) *> ZHandler.sendStream(ZStream.empty)))
        yield assertTrue(call.response.status == 204, call.response.ended, call.response.chunks.isEmpty)
      },
      test("request resources stay open until the stream ends, then close once") {
        var open = false
        var releases = 0
        val call = Call("/stream")
        val handler = ZHandler.define { _ =>
          ZIO.acquireRelease(ZIO.succeed { open = true })(_ => ZIO.succeed { open = false; releases += 1 }) *>
            ZHandler.sendStream(ZStream.fromZIO(ZIO.succeed(open.toString)) ++ ZStream.fromZIO(ZIO.succeed(open.toString)))
        }
        for _ <- call.invoke(handler)
        yield assertTrue(call.response.chunks.toList == List("true", "true"), !open, releases == 1)
      },
      // Z14, C7
      test("disconnect interrupts the stream and releases stream and request resources once") {
        var released = 0
        var streamReleased = 0
        val call = Call("/stream")
        val stream = ZStream.unwrapScoped(
          ZIO.acquireRelease(ZIO.unit)(_ => ZIO.succeed { streamReleased += 1 }).as(ZStream("first") ++ ZStream.never),
        )
        val handler = ZHandler.define(_ => ZIO.acquireRelease(ZIO.unit)(_ => ZIO.succeed { released += 1 }) *> ZHandler.sendStream(stream))
        for
          running <- call.invoke(handler).fork
          _ <- ZIO.fromFuture(_ => call.response.firstWrite.future)
          _ <- ZIO.succeed(call.response.disconnect())
          body <- running.join
        yield assertTrue(
          js.isUndefined(body), released == 1, streamReleased == 1,
          call.response.chunks.toList == List("first"), !call.response.hasListener,
        )
      },
      // Z15
      test("a response closed before the stream starts completes without starting it") {
        var started = false
        val call = Call("/stream")
        call.response.disconnect()
        for body <- call.invoke(ZHandler.define(_ => ZIO.succeed { started = true } *> ZHandler.sendStream(ZStream("never"))))
        yield assertTrue(js.isUndefined(body), !started, !call.response.hasListener)
      },
      // Z16
      test("interruption before the first write waits for the stream's asynchronous cleanup") {
        val call = Call("/stream")
        for
          acquired <- Promise.make[Nothing, Unit]
          finalizing <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          released <- Ref.make(0)
          stream = ZStream.unwrapScoped(
            ZIO.acquireRelease(acquired.succeed(()))(_ => finalizing.succeed(()) *> release.await *> released.update(_ + 1))
              .as(ZStream.never: ZStream[Any, Nothing, String]),
          )
          running <- call.invoke(ZHandler.define(_ => ZHandler.sendStream(stream))).fork
          _ <- acquired.await
          _ <- ZIO.succeed(call.response.disconnect())
          _ <- finalizing.await
          _ <- turns(3)
          pending <- running.poll
          _ <- release.succeed(())
          _ <- running.join
          count <- released.get
        yield assertTrue(pending.isEmpty, count == 1, call.response.chunks.isEmpty, !call.response.hasListener)
      },
      // Z17
      test("a failure after the first chunk destroys the socket and keeps written chunks") {
        val call = Call("/stream")
        val stream = ZStream("prefix") ++ ZStream.fail(new Exception("private details"))
        for body <- call.invoke(ZHandler.define(_ => ZHandler.sendStream(stream)))
        yield assertTrue(js.isUndefined(body), call.response.destroyed, call.response.chunks.toList == List("prefix"), !call.response.hasListener)
      },
      // claude 2026-10-20: changed: the head leaves at the call, so a failure before the first element can only end the stream (ADR-0004, Q1)
      test("a failure before the first element ends the stream: the status stays and the connection is closed") {
        val call = Call("/stream")
        val handler = ZHandler.define { _ =>
          Response.status(Status.Accepted) *> Response.header("x-sent", "1") *>
            ZHandler.sendStream(ZStream.fail(HttpError.conflict("Busy")): ZStream[Any, HttpError, String])
        }
        for body <- call.invoke(handler)
        yield assertTrue(
          js.isUndefined(body), call.response.status == 202, call.response.header("x-sent").contains("1"),
          call.response.chunks.isEmpty, call.response.destroyed, !call.response.ended,
        )
      },
      // claude 2026-10-20: changed: the head leaves at the call; the client sees no error data, only an ended connection (ADR-0004, Q1)
      test("a SafeError thrown inside a stream never reaches the client; a null element ends the stream too") {
        final case class Approved(message: String) extends SafeError:
          override def status: Int = 409
        val thrown = Call("/stream")
        val nullElement = Call("/stream")
        for
          thrownBody <- thrown.invoke(ZHandler.define(_ => ZHandler.sendStream(ZStream("x").map[String](_ => throw Approved("private detail")))))
          nullBody <- nullElement.invoke(ZHandler.define(_ => ZHandler.sendStream(ZStream[String](null))))
        yield assertTrue(
          js.isUndefined(thrownBody), thrown.response.status == 200, thrown.response.chunks.isEmpty, thrown.response.destroyed,
          js.isUndefined(nullBody), nullElement.response.chunks.isEmpty, nullElement.response.destroyed,
        )
      },
      test("a stream without a content type is sent as text/plain with nosniff") {
        val call = Call("/stream")
        for _ <- call.invoke(ZHandler.define(_ => ZHandler.sendStream(ZStream("a"))))
        yield assertTrue(
          call.response.header("content-type").contains("text/plain; charset=utf-8"),
          call.response.header("x-content-type-options").contains("nosniff"),
          call.response.chunks.toList == List("a"),
        )
      },
      // Z18
      // claude 2026-10-19: changed: a socketless response (Nitro local fetch) is no longer rejected: the head is written first and the stream is handed over unread, as UTF-8 bytes
      test("without a Node socket, the head is written first and the stream is handed over unread") {
        var acquired = 0
        val call = Call("/stream", withSocket = false)
        val stream = ZStream.fromZIO(ZIO.succeed { acquired += 1; "data" })
        for
          body <- call.invoke(ZHandler.define(_ => ZHandler.sendStream(stream)))
          before = acquired
          text <- readText(call.response.native._data)
        yield assertTrue(
          js.isUndefined(body), before == 0, acquired == 1, text == "data", call.response.status == 200,
          call.response.header("x-h34s-stream").contains("1"), call.response.chunks.isEmpty,
        )
      },
      // claude 2026-10-19: created: decision 1's negative compile check for ZIO
      test("returning a ZStream does not compile, and the message names ZHandler.sendStream") {
        for result <- typeCheck("ZHandler.define(_ => ZIO.succeed(ZStream(\"a\")))")
        yield assertTrue(
          result.left.exists(_.contains("h34s cannot send zio.stream.ZStream[Any, Nothing, String] as a response body")),
          result.left.exists(_.contains("ZHandler.sendStream(stream)")),
        )
      },
    ) @@ TestAspect.timeout(45.seconds)

    // claude 2026-10-19: created: reads a Web stream of UTF-8 chunks to its end
    private def readText(stream: js.Dynamic): Task[String] =
      val reader  = stream.getReader()
      val decoder = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)()
      def loop(acc: String): Task[String] =
        ZIO.fromPromiseJS(reader.read().asInstanceOf[js.Promise[js.Dynamic]]).flatMap { step =>
          if step.done.asInstanceOf[Boolean] then ZIO.succeed(acc) else loop(acc + decoder.decode(step.value).asInstanceOf[String])
        }
      loop("")
end HttpStreamSpec
