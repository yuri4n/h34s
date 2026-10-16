package dev.yuri4n.exts.kyocats

import cats.effect.{IO, Resource}
import kyo.*
import munit.FunSuite

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.scalajs.js

class CatsEffectSuite extends FunSuite:
    given ExecutionContext = ExecutionContext.global
    override val munitTimeout: FiniteDuration = 60.seconds

    private var gates = List.empty[Promise[Unit]]
    private def gate(): Promise[Unit] =
      val value = Promise[Unit]()
      gates = value :: gates
      value

    override def afterEach(context: AfterEach): Unit =
      gates.foreach(_.trySuccess(()))
      gates = Nil

    private def signal(value: Promise[Unit]): IO[Unit] = IO { value.trySuccess(()); () }
    private def await(value: Promise[Unit]): IO[Unit] = IO.fromFuture(IO.pure(value.future))
    private def nextTurn: Future[Unit] =
      val completed = Promise[Unit]()
      js.timers.setTimeout(0) { completed.success(()); () }
      completed.future

    // Join a scoped worker without interrupting its supervisor. Kyo RC7 drains
    // Scope.run cleanup asynchronously when that supervisor itself is interrupted.
    private def start[A](program: => A < (Async & Scope & Abort[Throwable])) =
      import kyo.AllowUnsafe.embrace.danger
      val interrupt = Promise[() => Unit]()
      val supervised = Scope.run {
        Fiber.init(program).map { worker =>
          Sync.defer { interrupt.success(() => { worker.unsafe.interrupt(); () }); () }
            .andThen(worker.get)
        }
      }
      val result = Fiber.Unsafe.init(Abort.run[Throwable](supervised)).toFuture()
      (result, interrupt.future)

    private def run[A](program: => A < (Async & Scope & Abort[Throwable])): Future[Result[Throwable, A]] =
      start(program)._1

    private def success[A](result: Result[Throwable, A]): A = result.fold(
      identity,
      error => fail("Unexpected typed failure", error),
      error => fail("Unexpected panic", error),
    )

    private def failure[A](result: Result[Throwable, A]): Throwable =
      assert(result.isFailure, "IO failures must enter Abort[Throwable], not panic")
      result.fold(
        _ => fail("Expected failure"),
        identity,
        error => fail("Unexpected panic", error),
      )

    private def cancelled[A](outcome: scala.util.Try[Result[Throwable, A]]): Unit =
      outcome.foreach(_.fold(
        _ => fail("An interrupted worker must not return a successful value"),
        _ => (),
        _ => (),
      ))

    test("constructing and running an IO is lazy and repeatable") {
      var constructed = 0
      var executed = 0
      val program = CatsEffect.run {
        constructed += 1
        IO { executed += 1; executed }
      }
      assertEquals(constructed, 0)
      assertEquals(executed, 0)
      for
        first <- run(program)
        second <- run(program)
      yield
        assertEquals(success(first), 1)
        assertEquals(success(second), 2)
        assertEquals(constructed, 2)
        assertEquals(executed, 2)
    }

    test("success waits for Resource release exactly once") {
      val releasing = Promise[Unit]()
      val release = gate()
      var released = 0
      val io = Resource.make(IO.pure(42))(_ =>
        signal(releasing) *> await(release) *> IO { released += 1 },
      ).use(IO.pure)
      val result = run(CatsEffect.run(io))
      for
        _ <- releasing.future
        _ <- nextTurn
        _ = assert(!result.isCompleted)
        _ = release.success(())
        completed <- result
      yield
        assertEquals(success(completed), 42)
        assertEquals(released, 1)
    }

    for phase <- List("construction", "acquisition", "use", "release") do
      test(s"$phase failures are typed, generic and retain their cause") {
        val privateError = new IllegalStateException(s"private $phase detail")
        var released = 0
        def io: IO[String] = phase match
            case "construction" => throw privateError
            case "acquisition" => Resource.make(IO.raiseError[Unit](privateError))(_ => IO.unit).use(_ => IO.pure("ok"))
            case "use" => Resource.make(IO.unit)(_ => IO { released += 1 }).use(_ => IO.raiseError(privateError))
            case _ => Resource.make(IO.unit)(_ => IO { released += 1 } *> IO.raiseError(privateError)).use(_ => IO.pure("ok"))
        run(CatsEffect.run(io)).map { result =>
          val error = failure(result)
          assertEquals(error.getClass, classOf[RuntimeException])
          assertEquals(error.getMessage, "Cats Effect operation failed")
          assert(error.getCause eq privateError)
          assertEquals(released, if phase == "use" || phase == "release" then 1 else 0)
        }
      }

    test("self-cancelled Cats IO becomes a typed failure instead of a rejected Kyo Future") {
      run(CatsEffect.run(IO.canceled *> IO.pure("unreachable"))).map { result =>
        val error = failure(result)
        assertEquals(error.getMessage, "Cats Effect operation failed")
        assert(error.getCause.isInstanceOf[java.util.concurrent.CancellationException])
      }
    }

    test("cancellation during acquisition releases already acquired resources") {
      val acquiring = Promise[Unit]()
      val releasing = Promise[Unit]()
      val release = gate()
      var released = 0
      val io = Resource.make(IO.unit)(_ => signal(releasing) *> await(release) *> IO { released += 1 })
        .flatMap(_ => Resource.eval(signal(acquiring) *> IO.never[String]))
        .use(IO.pure)
      val (result, interrupt) = start(CatsEffect.run(io))
      for
        _ <- acquiring.future
        cancel <- interrupt
        _ = cancel()
        _ <- releasing.future
        _ <- nextTurn
        _ = assert(!result.isCompleted)
        _ = release.success(())
        stopped <- result.transform(outcome => scala.util.Success(outcome))
      yield
        cancelled(stopped)
        assertEquals(released, 1)
    }

    test("cancellation during use awaits all nested finalizers in reverse acquisition order") {
      val started = Promise[Unit]()
      val releasing = Promise[Unit]()
      val release = gate()
      var finalized = List.empty[String]
      val io = Resource.make(IO.unit)(_ => IO { finalized = finalized :+ "outer" })
        .flatMap(_ => Resource.make(IO.unit)(_ =>
          signal(releasing) *> await(release) *> IO { finalized = finalized :+ "inner" },
        )).use(_ => signal(started) *> IO.never[String])
      val (result, interrupt) = start(CatsEffect.run(io))
      for
        _ <- started.future
        cancel <- interrupt
        _ = cancel()
        _ <- releasing.future
        _ <- nextTurn
        _ = assert(!result.isCompleted)
        _ = assertEquals(finalized, Nil)
        _ = release.success(())
        stopped <- result.transform(outcome => scala.util.Success(outcome))
      yield
        cancelled(stopped)
        assertEquals(finalized, List("inner", "outer"))
    }

    test("cancellation during an uncancelable Resource release still awaits release once") {
      val releasing = Promise[Unit]()
      val release = gate()
      var released = 0
      val io = Resource.make(IO.unit)(_ =>
        signal(releasing) *> await(release) *> IO { released += 1 },
      ).use(_ => IO.pure("ok"))
      val (result, interrupt) = start(CatsEffect.run(io))
      for
        _ <- releasing.future
        cancel <- interrupt
        _ = cancel()
        _ <- nextTurn
        _ = assert(!result.isCompleted)
        _ = release.success(())
        stopped <- result.transform(outcome => scala.util.Success(outcome))
      yield
        cancelled(stopped)
        assertEquals(released, 1)
    }
end CatsEffectSuite
