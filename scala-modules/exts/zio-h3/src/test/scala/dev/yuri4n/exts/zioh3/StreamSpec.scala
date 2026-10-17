package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.kyoh3.ResponseBody
import dev.yuri4n.exts.kyoh3.internal.StreamResponse
import dev.yuri4n.exts.zioh3.ZioTesting.turns
import kyo.{AllowUnsafe, Sync}
import org.getshaka.nativeconverter.{NativeConverter, ParseState}
import zio.*
import zio.stream.ZStream
import zio.test.*

import scala.scalajs.js

/** ZStreams as pull-driven Web streams through the core (Z19-Z24). */
object StreamSpec extends ZIOSpecDefault:
    /** Encodes through the same `ZResponseBody` instance that handlers use. */
    private def reader[E <: Throwable, A: NativeConverter](stream: ZStream[Any, E, A]): UIO[js.Dynamic] =
      ZIO.succeed {
        import AllowUnsafe.embrace.danger
        Sync.Unsafe.evalOrThrow(summon[ZResponseBody[ZStream[Any, E, A]]].toCore(stream, Runtime.default)) match
            case ResponseBody.Outgoing.Streamed(chunks) => StreamResponse.readable(chunks).asInstanceOf[js.Dynamic].getReader()
            case other                                  => throw IllegalStateException(s"expected a stream, got $other")
      }

    private def read(reader: js.Dynamic): Task[js.Dynamic] =
      ZIO.fromPromiseJS(reader.read().asInstanceOf[js.Promise[js.Dynamic]])

    private def readAll(reader: js.Dynamic): Task[List[js.Any]] =
      read(reader).flatMap { step =>
        if step.done.asInstanceOf[Boolean] then ZIO.succeed(Nil)
        else readAll(reader).map(rest => step.value.asInstanceOf[js.Any] :: rest)
      }

    private def cancel(reader: js.Dynamic): Task[Unit] =
      ZIO.fromPromiseJS(reader.cancel().asInstanceOf[js.Promise[Unit]])

    def spec = suite("ZStream Web streams")(
      // Z19
      test("the Web stream is pull-driven and does not drain upstream before consumption") {
        for
          produced <- Ref.make(0)
          first <- Promise.make[Nothing, Unit]
          consumer <- reader(ZStream.repeatZIO(produced.updateAndGet(_ + 1).tap(_ => first.succeed(()))).take(100))
          _ <- first.await
          _ <- turns(20)
          count <- produced.get
          _ <- cancel(consumer)
        yield assertTrue(count <= 1)
      },
      // Z20
      test("cancellation waits for the stream's asynchronous scoped finalizer") {
        for
          acquired <- Promise.make[Nothing, Unit]
          finalizing <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          finished <- Ref.make(0)
          consumer <- reader(ZStream.unwrapScoped(
            ZIO.acquireRelease(acquired.succeed(()))(_ => finalizing.succeed(()) *> release.await *> finished.update(_ + 1))
              .as(ZStream.never: ZStream[Any, Nothing, String]),
          ))
          _ <- acquired.await
          cancelling <- cancel(consumer).fork
          _ <- finalizing.await
          _ <- turns(3)
          pending <- cancelling.poll
          _ <- release.succeed(())
          _ <- cancelling.join
          count <- finished.get
        yield assertTrue(pending.isEmpty, count == 1)
      },
      // Z21
      test("the stream scope finalizes exactly once after EOF") {
        for
          released <- Ref.make(0)
          consumer <- reader(ZStream.unwrapScoped(ZIO.acquireRelease(ZIO.unit)(_ => released.update(_ + 1)).as(ZStream("x"))))
          value <- read(consumer)
          end <- read(consumer)
          afterEnd <- released.get
          _ <- cancel(consumer)
          count <- released.get
        yield assertTrue(
          !value.done.asInstanceOf[Boolean], value.value.asInstanceOf[String] == "x",
          end.done.asInstanceOf[Boolean], afterEnd == 1, count == 1,
        )
      },
      // Z22
      test("a throwing element converter rejects the read and closes the stream scope once") {
        final case class Broken(value: String)
        given NativeConverter[Broken]:
          extension (value: Broken) def toNative: js.Any = throw IllegalArgumentException("conversion failed")
          def fromNative(state: ParseState): Broken = Broken("")
        for
          released <- Ref.make(0)
          consumer <- reader(ZStream.unwrapScoped(ZIO.acquireRelease(ZIO.unit)(_ => released.update(_ + 1)).as(ZStream(Broken("x")))))
          result <- read(consumer).either
          count <- released.get
        yield assertTrue(result.isLeft, count == 1)
      },
      // Z23
      test("finite streams keep chunk order; empty streams end; failures reject the read") {
        final case class Item(value: Int)
        given NativeConverter[Item]:
          extension (item: Item) def toNative: js.Any = js.Dynamic.literal(value = item.value)
          def fromNative(state: ParseState): Item = Item(state.json.asInstanceOf[js.Dynamic].value.asInstanceOf[Int])
        for
          letters <- reader(ZStream.fromIterable(List("a", "b", "c"))).flatMap(readAll)
          empty <- reader(ZStream.empty: ZStream[Any, Nothing, String]).flatMap(read)
          failed <- reader(ZStream.fail(new Exception("test error")): ZStream[Any, Exception, Int]).flatMap(read).either
          items <- reader(ZStream(Item(1), Item(2), Item(3))).flatMap(readAll)
        yield assertTrue(
          letters.map(_.asInstanceOf[String]) == List("a", "b", "c"),
          empty.done.asInstanceOf[Boolean],
          failed.isLeft,
          items.map(value => summon[NativeConverter[Item]].fromNative(value)) == List(Item(1), Item(2), Item(3)),
        )
      },
      // Z24
      test("effectful, concatenated, filtered and mapped streams keep order and values") {
        for
          counter <- Ref.make(0)
          effects <- reader(ZStream.repeatZIO(counter.updateAndGet(_ + 1)).take(5)).flatMap(readAll)
          concatenated <- reader(ZStream(1, 2) ++ ZStream(3, 4)).flatMap(readAll)
          filtered <- reader(ZStream(1, 2, 3, 4, 5, 6).filter(_ % 2 == 0)).flatMap(readAll)
          mapped <- reader(ZStream(1, 2, 3).map(_ * 10)).flatMap(readAll)
        yield assertTrue(
          effects.map(_.asInstanceOf[Int]) == List(1, 2, 3, 4, 5),
          concatenated.map(_.asInstanceOf[Int]) == List(1, 2, 3, 4),
          filtered.map(_.asInstanceOf[Int]) == List(2, 4, 6),
          mapped.map(_.asInstanceOf[Int]) == List(10, 20, 30),
        )
      },
    ) @@ TestAspect.timeout(45.seconds)
end StreamSpec
