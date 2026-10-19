package dev.yuri4n.exts.zioh3.internal

import dev.yuri4n.exts.kyoh3.internal.Supervised
import kyo.{Abort, Async, Frame, Scope, `<`}
import zio.{Task, ZIO}

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
end KyoCall
