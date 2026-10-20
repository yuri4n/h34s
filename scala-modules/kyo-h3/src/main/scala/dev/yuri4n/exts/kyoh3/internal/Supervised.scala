package dev.yuri4n.exts.kyoh3.internal

import kyo.{Abort, AllowUnsafe, Async, Fiber, Frame, Result, Scope, Sync, `<`}

import scala.scalajs.js

/** Runs a scoped program on a worker fiber under a supervisor that is never interrupted.
  *
  * `interrupt()` interrupts only the worker. The supervisor then closes the worker's scope and
  * waits for every finalizer (also asynchronous ones) before it completes. Interrupting a fiber
  * that runs `Scope.run` itself would let Kyo drain its finalizers on a detached fiber, so nobody
  * could wait for them. This is the request and stream lifecycle of h34s.
  */
private[exts] final class Supervised[A](program: => A < (Async & Abort[Throwable] & Scope))(using Frame):
    import AllowUnsafe.embrace.danger

    private var interruptWorker: () => Unit = () => ()
    private var interruptRequested          = false

    private val supervisor: Fiber.Unsafe[Result[Throwable, A], Any] =
      Fiber.Unsafe.init(
        Scope.run(
          Fiber.init(program).map { started =>
            Sync.defer {
              interruptWorker = () => { started.unsafe.interrupt(); () }
              if interruptRequested then interruptWorker()
            }.andThen(started.getResult)
          },
        ),
      )

    /** Interrupts the worker; its scope still closes and is awaited. Idempotent. */
    def interrupt(): Unit =
      interruptRequested = true
      interruptWorker()

    /** Completed by the supervisor. Uninterruptible, so a waiter that is interrupted cannot reach the
      * supervisor and turn its awaited drain into a detached one.
      */
    private val completed = Fiber.Promise.Unsafe.initUninterruptible[Result[Throwable, A], Any]()

    supervisor.onComplete { outcome =>
      val result: Result[Throwable, A] =
        outcome.fold(_.eval, _ => Result.panic(IllegalStateException("Supervisor failed")), error => Result.panic(error))
      completed.completeDiscard(Result.succeed(result))
    }

    /** Runs once, after the worker finished and its scope closed. */
    def onComplete(callback: Result[Throwable, A] => Unit): Unit =
      completed.onComplete(outcome => outcome.fold(result => callback(result.eval), _ => (), error => callback(Result.panic(error))))

    def await(using Frame): Result[Throwable, A] < Async = completed.safe.get

    def promise(): js.Promise[Unit] =
      new js.Promise[Unit]((resolve, _) => onComplete(_ => resolve(())))
end Supervised
