package dev.yuri4n.exts.zioh3.internal

import dev.yuri4n.exts.kyoh3.internal.Supervised
import kyo.{Abort, Async, Frame, Kyo, Scope, Sync, `<`}
import zio.{IO, Task, UIO, ZIO}

// claude 2026-10-19: created: a scoped Kyo program run from ZIO, whose interruption waits for the Kyo scope (ZHandler.sendStream)
/** A scoped Kyo program run from a ZIO fiber.
  *
  * The program runs on a Kyo worker under a never-interrupted supervisor (`Supervised`). When the
  * ZIO fiber is interrupted, the canceler interrupts the worker and waits until its scope and every
  * finalizer have finished, so the ZIO fiber's own finalizers run after the Kyo ones. kyo-zio's
  * `ZIOs.get` is not used, because its interruption does not wait.
  */
private[exts] object KyoCall:
    def run[A](program: => A < (Async & Abort[Throwable] & Scope))(using Frame): Task[A] =
      ZIO.asyncInterrupt[Any, Throwable, A] { callback =>
        val supervised = new Supervised[A](program)
        supervised.onComplete { result =>
          callback(result.foldError(
            value => ZIO.succeed(value),
            error => if error.isFailure then ZIO.fail(error.failureOrPanic) else ZIO.die(error.failureOrPanic),
          ))
        }
        Left(ZIO.async[Any, Nothing, Unit] { done =>
          supervised.interrupt()
          supervised.onComplete(_ => done(ZIO.unit))
        })
      }

    // claude 2026-10-20: created: a Kyo program whose Kyo scope lives as long as the caller's ZIO Scope (the ZIO $fetch, ADR-0005 gap 6)
    /** Runs a scoped Kyo program and gives its value, but keeps the program's Kyo scope open until
      * the current ZIO `Scope` closes, when `keepOpen(value)`; otherwise the Kyo scope closes once
      * the value is given.
      *
      * The finalizer is added to the ZIO `Scope` in the same uninterruptible step that starts the
      * worker, so an interrupt cannot separate them. Closing the ZIO `Scope` interrupts the worker
      * and waits until its Kyo scope and every finalizer have finished, also when the caller was
      * interrupted before the value arrived.
      */
    def scoped[A](program: => A < (Async & Abort[Throwable] & Scope), keepOpen: A => Boolean)(using
        Frame,
    ): ZIO[zio.Scope, Throwable, A] =
      ZIO.uninterruptibleMask { restore =>
        ZIO.suspendSucceed {
          val session = new Session[A](program, keepOpen)
          ZIO.addFinalizer(session.close) *> restore(session.value)
        }
      }

    /** One run of `scoped`: the worker, and its first outcome for the ZIO side. Scala.js runs both
      * sides on one thread, so the two fields need no lock.
      */
    private final class Session[A](program: => A < (Async & Abort[Throwable] & Scope), keepOpen: A => Boolean)(using Frame):
        private var outcome: Option[IO[Throwable, A]]           = None
        private var waiter: Option[IO[Throwable, A] => Unit] = None

        /** Keeps the first outcome only: a failure after the value changes nothing. */
        private def deliver(first: IO[Throwable, A]): Unit =
          if outcome.isEmpty then
              outcome = Some(first)
              waiter.foreach(_(first))

        private val supervised = new Supervised[Unit](
          program.map(value =>
            Sync.defer(deliver(ZIO.succeed(value))).andThen(if keepOpen(value) then Async.never[Unit] else Kyo.unit),
          ),
        )
        // A failure before the value; after it, the promise is already done and this changes nothing.
        supervised.onComplete(result =>
          result.foldError(
            _ => (),
            error => deliver(if error.isFailure then ZIO.fail(error.failureOrPanic) else ZIO.die(error.failureOrPanic)),
          ),
        )

        def value: IO[Throwable, A] =
          ZIO.async[Any, Throwable, A] { callback =>
            outcome match
                case Some(first) => callback(first)
                case None        => waiter = Some(callback)
          }

        /** Interrupts the worker and waits until its Kyo scope has closed. */
        def close: UIO[Unit] =
          ZIO.async[Any, Nothing, Unit] { done =>
            supervised.interrupt()
            supervised.onComplete(_ => done(ZIO.unit))
          }
    end Session
end KyoCall
