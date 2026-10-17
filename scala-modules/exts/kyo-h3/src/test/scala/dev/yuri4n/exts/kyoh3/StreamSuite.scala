package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.kyoh3.KyoTesting.*
import dev.yuri4n.exts.kyoh3.internal.StreamResponse
import kyo.*
import munit.FunSuite
import org.getshaka.nativeconverter.{NativeConverter, ParseState}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.scalajs.js

/** Kyo streams as pull-driven Web streams (the ZIO Z19-Z24 contracts, ported). */
class StreamSuite extends FunSuite:
    given ExecutionContext = ExecutionContext.global
    override val munitTimeout: FiniteDuration = 60.seconds

    /** Encodes with the core's internal stream encoding, so the bridge is tested with any element
      * type (handlers may return only `Stream[String, S]`; see `stringReader`).
      */
    private def reader[A: Tag, S >: ResponseBody.StreamEffects](stream: Stream[A, S])(using
        converter: NativeConverter[A],
    ): js.Dynamic =
      StreamResponse.readable(ResponseBody.chunks(stream)(converter.toNative(_))).asInstanceOf[js.Dynamic].getReader()

    /** Encodes through the same `ResponseBody` instance that handlers use. */
    private def stringReader[S >: ResponseBody.StreamEffects](stream: Stream[String, S])(using
        body: ResponseBody[Stream[String, S]],
    ): js.Dynamic =
      import AllowUnsafe.embrace.danger
      Sync.Unsafe.evalOrThrow(body.encode(stream)) match
          case ResponseBody.Outgoing.Streamed(chunks) => StreamResponse.readable(chunks).asInstanceOf[js.Dynamic].getReader()
          case other                                  => fail(s"expected a stream, got $other")

    private def read(reader: js.Dynamic): Future[js.Dynamic] =
      reader.read().asInstanceOf[js.Promise[js.Dynamic]].toFuture

    private def readAll(reader: js.Dynamic): Future[List[js.Any]] =
      read(reader).flatMap { step =>
        if step.done.asInstanceOf[Boolean] then Future.successful(Nil)
        else readAll(reader).map(rest => step.value.asInstanceOf[js.Any] :: rest)
      }

    private def cancel(reader: js.Dynamic): Future[Unit] =
      reader.cancel().asInstanceOf[js.Promise[Unit]].toFuture

    // Z19
    test("the Web stream is pull-driven and does not drain upstream before consumption") {
      var produced = 0
      val first = Promise[Unit]()
      val stream = Stream.unfold(0, chunkSize = 1) { (index: Int) =>
        Sync.defer {
          produced += 1
          first.trySuccess(())
          if index < 100 then Maybe((index, index + 1)) else Maybe.empty
        }
      }
      val consumer = reader(stream)
      for
        _ <- first.future
        _ <- turns(20)
        count = produced
        _ <- cancel(consumer)
      yield assert(count <= 1, s"produced $count elements before any read")
    }

    // Z20
    test("cancellation waits for the stream's asynchronous scoped finalizer") {
      val acquired = Promise[Unit]()
      val finalizing = Promise[Unit]()
      val release = Promise[Unit]()
      var finished = 0
      val stream = Stream[String, Async & Scope] {
        Scope.acquireRelease(Sync.defer { acquired.trySuccess(()); () }) { _ =>
          Sync.defer { finalizing.trySuccess(()); () }
            .andThen(Async.fromFuture(release.future))
            .andThen(Sync.defer { finished += 1 })
        }.andThen(Async.never[Unit])
      }
      val consumer = reader(stream)
      val checked = for
        _ <- acquired.future
        cancelling = cancel(consumer)
        _ <- finalizing.future
        _ <- turns(3)
        pending = !cancelling.isCompleted
        _ = release.success(())
        _ <- cancelling
      yield
        assert(pending, "cancel resolved before the finalizer finished")
        assertEquals(finished, 1)
      TestDeadline(checked).andThen { case _ => release.trySuccess(()) }
    }

    // Z21
    test("the stream scope finalizes exactly once after EOF") {
      var released = 0
      val stream = Stream[String, Async & Scope] {
        Scope.acquireRelease(Sync.defer(()))(_ => Sync.defer { released += 1 })
          .andThen(Emit.value(Chunk("x")))
      }
      val consumer = reader(stream)
      for
        value <- read(consumer)
        end <- read(consumer)
        afterEnd = released
        _ <- cancel(consumer)
      yield
        assert(!value.done.asInstanceOf[Boolean])
        assertEquals(value.value.asInstanceOf[String], "x")
        assert(end.done.asInstanceOf[Boolean])
        assertEquals(afterEnd, 1)
        assertEquals(released, 1)
    }

    // Z22
    test("a throwing element converter rejects the read and closes the stream scope once") {
      final case class Broken(value: String)
      given NativeConverter[Broken]:
        extension (value: Broken) def toNative: js.Any = throw IllegalArgumentException("conversion failed")
        def fromNative(state: ParseState): Broken = Broken("")
      var released = 0
      val stream = Stream[Broken, Async & Scope] {
        Scope.acquireRelease(Sync.defer(()))(_ => Sync.defer { released += 1 })
          .andThen(Emit.value(Chunk(Broken("x"))))
      }
      read(reader(stream)).transform(result => scala.util.Success(result)).map { result =>
        assert(result.isFailure, "the read must reject")
        assertEquals(released, 1)
      }
    }

    // Z23
    test("finite streams keep chunk order; empty streams end; failures reject the read") {
      final case class Item(value: Int)
      given NativeConverter[Item]:
        extension (item: Item) def toNative: js.Any = js.Dynamic.literal(value = item.value)
        def fromNative(state: ParseState): Item = Item(state.json.asInstanceOf[js.Dynamic].value.asInstanceOf[Int])
      for
        letters <- readAll(stringReader(Stream.init(Seq("a", "b", "c"))))
        empty <- read(reader(Stream.empty[String]))
        failed <- read(reader(Stream[Int, Abort[Exception]](Abort.fail(new Exception("test error"))))).transform(r => scala.util.Success(r))
        items <- readAll(reader(Stream.init(Seq(Item(1), Item(2), Item(3)))))
      yield
        assertEquals(letters.map(_.asInstanceOf[String]), List("a", "b", "c"))
        assert(empty.done.asInstanceOf[Boolean])
        assert(failed.isFailure)
        assertEquals(items.map(value => summon[NativeConverter[Item]].fromNative(value)), List(Item(1), Item(2), Item(3)))
    }

    // Z24
    test("effectful, concatenated, filtered and mapped streams keep order and values") {
      var counter = 0
      val effectful = Stream.unfold(0, chunkSize = 1)((n: Int) => Sync.defer { counter += 1; if n < 5 then Maybe((counter, n + 1)) else Maybe.empty })
      for
        effects <- readAll(reader(effectful))
        concatenated <- readAll(reader(Stream.init(Seq(1, 2)).concat(Stream.init(Seq(3, 4)))))
        filtered <- readAll(reader(Stream.init(Seq(1, 2, 3, 4, 5, 6)).filter(_ % 2 == 0)))
        mapped <- readAll(reader(Stream.init(Seq(1, 2, 3)).map(_ * 10)))
      yield
        assertEquals(effects.map(_.asInstanceOf[Int]), List(1, 2, 3, 4, 5))
        assertEquals(concatenated.map(_.asInstanceOf[Int]), List(1, 2, 3, 4))
        assertEquals(filtered.map(_.asInstanceOf[Int]), List(2, 4, 6))
        assertEquals(mapped.map(_.asInstanceOf[Int]), List(10, 20, 30))
    }
end StreamSuite
