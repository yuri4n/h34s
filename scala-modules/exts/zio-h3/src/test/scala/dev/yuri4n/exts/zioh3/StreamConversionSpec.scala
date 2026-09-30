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
    )
end StreamConversionSpec
