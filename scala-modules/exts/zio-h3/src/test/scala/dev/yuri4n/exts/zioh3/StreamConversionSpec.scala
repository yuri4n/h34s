package dev.yuri4n.exts.zioh3

import org.getshaka.nativeconverter.{NativeConverter, ParseState}
import org.scalajs.dom
import zio.*
import zio.stream.ZStream
import zio.test.*

import scala.concurrent.ExecutionContext
import scala.scalajs.js

import NativeConverter.given

object StreamConversionSpec extends ZIOSpecDefault:

    given ExecutionContext = ExecutionContext.global

    private def readAllAs[A: NativeConverter](
        reader: dom.ReadableStreamReader[js.Any],
    ): Task[List[A]] =
      ZIO.fromPromiseJS(reader.read()).flatMap { chunk =>
        if chunk.done then ZIO.succeed(Nil)
        else
            for
                value <- chunk.value.fromNative_[A]
                rest  <- readAllAs[A](reader)
            yield value :: rest
      }

    def spec = suite("taskStreamToReadableStreamReader")(
      test("reads all chunks from a finite stream") {
        val stream = ZStream.fromIterable(List("a", "b", "c"))
        val reader = taskStreamToReadableStreamReader(stream)
        for values <- readAllAs[String](reader)
        yield assertTrue(values == List("a", "b", "c"))
      },
      test("handles empty streams") {
        val stream: TaskStream[String] = ZStream.empty
        val reader                     = taskStreamToReadableStreamReader(stream)
        for chunk <- ZIO.fromPromiseJS(reader.read())
        yield assertTrue(chunk.done)
      },
      test("handles stream errors") {
        val stream: TaskStream[Int] = ZStream.fail(new Exception("test error"))
        val reader                  = taskStreamToReadableStreamReader(stream)
        for result <- ZIO.fromPromiseJS(reader.read()).either
        yield assertTrue(result.isLeft)
      },
      test("handles NativeConverter for complex types") {
        case class Item(value: Int)

        given NativeConverter[Item] with
            extension (item: Item)
              def toNative: js.Any =
                js.Dynamic.literal(value = item.value)

            override def fromNative(parseState: ParseState): Item =
                val obj = parseState.json.asInstanceOf[js.Dynamic]
                Item(obj.value.asInstanceOf[Int])

        val stream = ZStream.fromIterable(List(Item(1), Item(2), Item(3)))
        val reader = taskStreamToReadableStreamReader(stream)
        for values <- readAllAs[Item](reader)
        yield assertTrue(values.map(_.value) == List(1, 2, 3))
      },
    ) + transportSuite

    // Created by Claude Code on 2026-10-13. These tests check that the converter returns a
    // pipeable Web ReadableStream, that it delivers every chunk through `pipeTo`, the way h3 1.x
    // `sendStream` consumes it, and that cancelling the stream interrupts the source fiber. They
    // do not call `SH3Event.sendStream`; the production smoke test in scripts/smoke-build.test.mjs
    // is the regression check for that boundary.
    private def transportSuite = suite("taskStreamToReadableStream")(
      test("returns a Web ReadableStream, not a reader") {
        val readable = taskStreamToReadableStream(ZStream.fromIterable(List("a", "b", "c")))
        val dynamic  = readable.asInstanceOf[js.Dynamic]
        for values <- readAllAs[String](readable.getReader())
        yield assertTrue(
          js.typeOf(dynamic.pipeTo) == "function",
          js.typeOf(dynamic.getReader) == "function",
          values == List("a", "b", "c"),
        )
      },
      test("pipes every chunk into a WritableStream the way h3 sends a response") {
        val received = scala.collection.mutable.ListBuffer.empty[String]
        val sink     = js.Dynamic.literal(
          write = js.Any.fromFunction1 { (chunk: js.Any) =>
              received += chunk.asInstanceOf[String]
              ()
          },
        )
        val writable = js.Dynamic.newInstance(js.Dynamic.global.WritableStream)(sink)
        val readable = taskStreamToReadableStream(ZStream.fromIterable(List("a", "b", "c")))
        for _ <- ZIO.fromPromiseJS(
              readable.asInstanceOf[js.Dynamic].pipeTo(writable).asInstanceOf[js.Promise[Unit]],
            )
        yield assertTrue(received.toList == List("a", "b", "c"))
      },
      test("a failing source rejects the pipe instead of completing it") {
        val sink     = js.Dynamic.literal(write = js.Any.fromFunction1((_: js.Any) => ()))
        val writable = js.Dynamic.newInstance(js.Dynamic.global.WritableStream)(sink)
        val failing: TaskStream[String] =
          ZStream("a") ++ ZStream.fail(new Exception("source failed"))
        val readable = taskStreamToReadableStream(failing)
        for result <- ZIO
              .fromPromiseJS(
                readable.asInstanceOf[js.Dynamic].pipeTo(writable).asInstanceOf[js.Promise[Unit]],
              )
              .either
        yield assertTrue(result.left.exists(_.getMessage == "source failed"))
      },
      // Created by Claude Code on 2026-10-13: pins the cancel contract stated in the scaladoc.
      test("cancelling the stream interrupts the source fiber") {
        for
            done  <- Promise.make[Nothing, Unit]
            source = ZStream.repeatZIO(Clock.sleep(10.millis).as("x")).ensuring(done.succeed(()))
            reader = taskStreamToReadableStream(source).getReader()
            first <- ZIO.fromPromiseJS(reader.read())
            _     <- ZIO.fromPromiseJS(reader.asInstanceOf[js.Dynamic].cancel().asInstanceOf[js.Promise[Unit]])
            _     <- done.await.timeoutFail(new Exception("source fiber was not interrupted"))(5.seconds)
        yield assertTrue(!first.done)
      } @@ TestAspect.withLiveClock,
    ) @@ TestAspect.timeout(10.seconds)
end StreamConversionSpec
