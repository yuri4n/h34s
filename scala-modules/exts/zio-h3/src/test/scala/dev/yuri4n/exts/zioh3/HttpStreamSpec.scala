package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.zioh3.ZioTesting.*
import org.getshaka.nativeconverter.{NativeConverter, ParseState}
import zio.*
import zio.stream.ZStream
import zio.test.*

import scala.scalajs.js

/** ZStream bodies through the core's h3 `sendStream` path (Z13-Z18). */
object HttpStreamSpec extends ZIOSpecDefault:
    def spec = suite("ZStream response bodies over h3")(
      // Z13, C12
      test("writes the head before the first chunk, then every chunk, and ends the response") {
        val call = Call("/stream")
        val handler = ZHandler.define { _ =>
          Response.status(Status.Accepted) *> Response.header("content-type", "text/plain") *> ZIO.succeed(ZStream("a", "b", "c"))
        }
        for body <- call.invoke(handler)
        yield assertTrue(
          js.isUndefined(body),
          call.response.headAtFirstWrite.contains(202 -> List("content-type")),
          call.response.chunks.toList == List("a", "b", "c"),
          call.response.ended, !call.response.hasListener, call.handled,
        )
      },
      test("an empty stream still writes the head and ends the response") {
        val call = Call("/stream")
        for _ <- call.invoke(ZHandler.define(_ => Response.status(Status.NoContent).as(ZStream.empty: ZStream[Any, Nothing, String])))
        yield assertTrue(call.response.status == 204, call.response.ended, call.response.chunks.isEmpty)
      },
      test("request resources stay open until the stream ends, then close once") {
        var open = false
        var releases = 0
        val call = Call("/stream")
        val handler = ZHandler.define { _ =>
          ZIO.acquireRelease(ZIO.succeed { open = true })(_ => ZIO.succeed { open = false; releases += 1 })
            .as(ZStream.fromZIO(ZIO.succeed(open.toString)) ++ ZStream.fromZIO(ZIO.succeed(open.toString)))
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
        val handler = ZHandler.define(_ => ZIO.acquireRelease(ZIO.unit)(_ => ZIO.succeed { released += 1 }).as(stream))
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
        for body <- call.invoke(ZHandler.define(_ => ZIO.succeed { started = true; ZStream("never") }))
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
          running <- call.invoke(ZHandler.define(_ => ZIO.succeed(stream))).fork
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
        for body <- call.invoke(ZHandler.define(_ => ZIO.succeed(stream)))
        yield assertTrue(js.isUndefined(body), call.response.destroyed, call.response.chunks.toList == List("prefix"), !call.response.hasListener)
      },
      test("a failure before the first chunk is rendered with the failure rule") {
        val call = Call("/stream")
        val handler = ZHandler.define { _ =>
          Response.status(Status.Accepted) *> Response.header("x-discarded", "1") *>
            ZIO.succeed(ZStream.fail(HttpError.conflict("Busy")): ZStream[Any, HttpError, String])
        }
        for body <- call.invoke(handler)
        yield assertTrue(
          call.response.status == 409, wire(body) == errors("Busy"),
          call.response.header("x-discarded").isEmpty, call.response.chunks.isEmpty,
        )
      },
      test("a SafeError thrown by an element converter is a defect, not an approved error") {
        final case class Leaky(value: String)
        final case class Approved(message: String) extends SafeError:
          override def status: Int = 409
        given NativeConverter[Leaky]:
          extension (value: Leaky) def toNative: js.Any = throw Approved("private converter detail")
          def fromNative(state: ParseState): Leaky = Leaky("")
        val call = Call("/stream")
        for body <- call.invoke(ZHandler.define(_ => ZIO.succeed(ZStream(Leaky("x")))))
        yield assertTrue(call.response.status == 500, wire(body) == opaque, call.response.chunks.isEmpty)
      },
      // Z18
      test("without a Node socket, streaming fails before acquiring stream resources") {
        var acquired = 0
        val call = Call("/stream", withSocket = false)
        val stream = ZStream.fromZIO(ZIO.succeed { acquired += 1; "data" })
        for body <- call.invoke(ZHandler.define(_ => ZIO.succeed(stream)))
        yield assertTrue(acquired == 0, call.response.status == 500, wire(body) == opaque)
      },
    ) @@ TestAspect.timeout(45.seconds)
end HttpStreamSpec
