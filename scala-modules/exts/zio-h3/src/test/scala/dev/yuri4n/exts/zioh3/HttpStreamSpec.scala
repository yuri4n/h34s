package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.h3.CommonEvHandler
import dev.yuri4n.h34s.facades.h3.mod.{EventHandlerRequest, H3Event}
import zio.*
import zio.stream.ZStream
import zio.test.*

import scala.concurrent.{Promise as FuturePromise}
import scala.scalajs.js

object HttpStreamSpec extends ZIOSpecDefault:
    private final class Response:
      val firstWrite = FuturePromise[Unit]()
      var closeListener: js.Function0[Unit] = () => ()
      var removed = false
      var ended = false
      val chunks = scala.collection.mutable.ListBuffer.empty[String]
      val native: js.Dynamic = js.Dynamic.literal(
        socket = js.Dynamic.literal(), statusCode = 200, headersSent = false, writableEnded = false,
        destroyed = false, closed = false,
      )
      native.updateDynamic("once")(((_: String, listener: js.Function0[Unit]) => closeListener = listener): js.Function2[String, js.Function0[Unit], Unit])
      native.updateDynamic("removeListener")(((_: String, _: js.Function0[Unit]) => removed = true): js.Function2[String, js.Function0[Unit], Unit])
      native.updateDynamic("write")(((chunk: String) => {
        chunks += chunk
        native.headersSent = true
        firstWrite.trySuccess(())
        ()
      }): js.Function1[String, Unit])
      native.updateDynamic("end")((() => { ended = true; () }): js.Function0[Unit])
      native.updateDynamic("destroy")((() => { native.destroyed = true; closeListener() }): js.Function0[Unit])
      def disconnect(): Unit =
        native.destroyed = true
        native.closed = true
        closeListener()
      def event = new H3Event[EventHandlerRequest](js.Dynamic.literal(method = "GET", url = "/stream", headers = js.Dynamic.literal()), native)
      def invoke(handler: CommonEvHandler): Task[js.Any] =
        ZIO.fromPromiseJS(handler(event).asInstanceOf[js.Promise[js.Any]])

    def spec = suite("h3 HTTP streaming")(
      test("writes a Web Stream and closes the response") {
        val response = new Response
        for _ <- response.invoke(ZHandler.define(ZHandler.sendStream(ZStream("a", "b", "c"))))
        yield assertTrue(response.chunks.toList == List("a", "b", "c"), response.ended, response.removed)
      },
      test("disconnect interrupts the stream and closes request resources") {
        val response = new Response
        for
          released <- Ref.make(0)
          streamReleased <- Ref.make(0)
          stream = ZStream.unwrapScoped(
            ZIO.acquireRelease(ZIO.unit)(_ => streamReleased.update(_ + 1)).as(ZStream("first") ++ ZStream.never)
          )
          handler = ZHandler.define(
            ZIO.acquireRelease(ZIO.unit)(_ => released.update(_ + 1)) *>
              ZHandler.sendStream(stream)
          )
          running <- response.invoke(handler).fork
          _ <- ZIO.fromFuture(_ => response.firstWrite.future)
          _ <- ZIO.succeed(response.disconnect())
          _ <- running.join
          count <- released.get
          streamCount <- streamReleased.get
        yield assertTrue(count == 1, streamCount == 1, response.removed)
      },
      test("handles a response closed before the stream starts") {
        val response = new Response
        response.disconnect()
        for _ <- response.invoke(ZHandler.define(ZHandler.sendStream(ZStream.never: ZStream[Any, Throwable, String])))
        yield assertTrue(response.removed)
      },
      test("effect interruption before the first write joins stream cleanup") {
        val response = new Response
        for
          acquired <- Promise.make[Nothing, Unit]
          released <- Ref.make(0)
          stream = ZStream.unwrapScoped(
            ZIO.acquireRelease(acquired.succeed(()))(_ => released.update(_ + 1))
              .as(ZStream.never: ZStream[Any, Throwable, String])
          )
          sending <- SH3Event(response.event).sendStream(stream).fork
          _ <- acquired.await
          _ <- sending.interrupt
          count <- released.get
        yield assertTrue(count == 1, response.removed)
      },
      test("terminates the socket if upstream fails after a chunk was sent") {
        val response = new Response
        for _ <- response.invoke(ZHandler.define(ZHandler.sendStream(ZStream("prefix") ++ ZStream.fail(new Exception("private details")))))
        yield assertTrue(response.native.destroyed.asInstanceOf[Boolean], response.chunks.toList == List("prefix"), response.removed)
      },
      test("rejects local-fetch streaming before acquiring stream resources") {
        val response = new Response
        response.native.socket = js.undefined
        for
          acquired <- Ref.make(0)
          stream = ZStream.fromZIO(acquired.update(_ + 1).as("data"))
          _ <- response.invoke(ZHandler.define(ZHandler.sendStream(stream)))
          count <- acquired.get
        yield assertTrue(count == 0, response.native.statusCode.asInstanceOf[Int] == 500)
      },
    ) @@ TestAspect.timeout(10.seconds)
end HttpStreamSpec
