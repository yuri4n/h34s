package dev.yuri4n.exts.zioh3

import org.getshaka.nativeconverter.NativeConverter
import org.scalajs.dom
import zio.*
import zio.stream.ZStream
import zio.test.*

import scala.concurrent.ExecutionContext
import scala.scalajs.js

import NativeConverter.given

object StreamConversionIntegrationSpec extends ZIOSpecDefault:

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

    def spec = suite("taskStreamToReadableStreamReader integration")(
      test("handles effect-based streams") {
        for
            ref    <- Ref.make(0)
            stream  = ZStream.repeatZIO(ref.updateAndGet(_ + 1)).take(5)
            reader  = taskStreamToReadableStreamReader(stream)
            values <- readAllAs[Int](reader)
        yield assertTrue(values == List(1, 2, 3, 4, 5))
      },
      test("handles concatenated streams") {
        val stream = ZStream.fromIterable(List(1, 2)) ++ ZStream.fromIterable(List(3, 4))
        val reader = taskStreamToReadableStreamReader(stream)
        for values <- readAllAs[Int](reader)
        yield assertTrue(values == List(1, 2, 3, 4))
      },
      test("handles filtered streams") {
        val stream = ZStream.fromIterable(List(1, 2, 3, 4, 5, 6)).filter(_ % 2 == 0)
        val reader = taskStreamToReadableStreamReader(stream)
        for values <- readAllAs[Int](reader)
        yield assertTrue(values == List(2, 4, 6))
      },
      test("handles mapped streams") {
        val stream = ZStream.fromIterable(List(1, 2, 3)).map(_ * 10)
        val reader = taskStreamToReadableStreamReader(stream)
        for values <- readAllAs[Int](reader)
        yield assertTrue(values == List(10, 20, 30))
      },
      test("maintains type safety through conversion") {
        val stream: TaskStream[String] = ZStream.fromIterable(List("hello", "world"))
        val reader                     = taskStreamToReadableStreamReader(stream)
        for values <- readAllAs[String](reader)
        yield assertTrue(values == List("hello", "world"))
      },
    )
end StreamConversionIntegrationSpec
