package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.kyoh3.KyoTesting.*
import kyo.*
import munit.FunSuite

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js

class SharedSuite extends FunSuite:
    given ExecutionContext = ExecutionContext.global

    private final class Counters:
        var acquired = 0
        var released = 0

    private def resource(counters: Counters, gate: Future[Unit] = Future.unit): Shared[Int] =
      Shared {
        Scope.acquireRelease(
          Async.fromFuture(gate).andThen(Sync.defer { counters.acquired += 1; counters.acquired }),
        )(_ => Sync.defer { counters.released += 1 })
      }

    // C10
    test("concurrent first gets share one acquisition") {
      val counters = Counters()
      val gate = Promise[Unit]()
      val shared = resource(counters, gate.future)
      val first = run(shared.get)
      val second = run(shared.get)
      gate.success(())
      for
        a <- first
        b <- second
        c <- run(shared.get)
        _ <- run(shared.close)
      yield
        assertEquals(List(a, b, c).map(_.getOrThrow), List(1, 1, 1))
        assertEquals(counters.acquired, 1)
        assertEquals(counters.released, 1)
    }

    test("a waiter that is interrupted does not cancel the shared acquisition") {
      val counters = Counters()
      val gate = Promise[Unit]()
      val shared = resource(counters, gate.future)
      import AllowUnsafe.embrace.danger
      val impatient = Fiber.Unsafe.init(Abort.run[Throwable](shared.get))
      val patient = run(shared.get)
      for
        _ <- turns(2)
        _ = impatient.interrupt()
        _ = gate.success(())
        value <- patient
        _ <- run(shared.close)
      yield
        assertEquals(value.getOrThrow, 1)
        assertEquals(counters.acquired, 1)
        assertEquals(counters.released, 1)
    }

    test("a failed acquisition is not cached") {
      var attempts = 0
      val shared = Shared[String] {
        Sync.defer(attempts += 1).andThen(
          if attempts == 1 then Abort.fail(IllegalStateException("database down")) else Sync.defer("connected"),
        )
      }
      for
        failed <- run(shared.get)
        succeeded <- run(shared.get)
      yield
        assertEquals(failed.failure.map(_.getMessage), Maybe("database down"))
        assertEquals(succeeded.getOrThrow, "connected")
        assertEquals(attempts, 2)
    }

    test("close releases once and a later get acquires again") {
      val counters = Counters()
      val shared = resource(counters)
      for
        _ <- run(shared.get)
        _ <- run(shared.close)
        _ <- run(shared.close)
        _ = assertEquals(counters.released, 1)
        again <- run(shared.get)
        _ <- run(shared.close)
      yield
        assertEquals(again.getOrThrow, 2)
        assertEquals(counters.released, 2)
    }

    test("the Nitro close hook releases every registered Shared value") {
      val closers = scala.collection.mutable.LinkedHashSet.empty[js.Function0[js.Any]]
      val onClose: js.Function1[js.Function0[js.Any], js.Function0[js.Any]] = close =>
        closers += close
        () => closers.remove(close)
      NitroGlobal.install(js.Dynamic.literal(version = 1, onClose = onClose))
      val first = Counters()
      val second = Counters()
      val one = resource(first)
      val two = resource(second)
      val test = for
        _ <- run(one.get)
        _ <- run(two.get)
        _ = assertEquals(closers.size, 2)
        _ <- Future.traverse(closers.toList)(close => close().asInstanceOf[js.Promise[js.Any]].toFuture)
        _ <- run(one.close)
      yield
        assertEquals(first.released, 1)
        assertEquals(second.released, 1)
        assertEquals(closers.size, 0)
      test.andThen { case _ => NitroGlobal.remove() }
    }
end SharedSuite
