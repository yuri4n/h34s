package dev.yuri4n.exts.zioh3.internal

import kyo.{AllowUnsafe, Async, Fiber, Frame, Kyo, Maybe, Result, Sync, `<`}
import zio.{Cause, Exit, FiberId, Runtime, Unsafe, ZIO}

import scala.scalajs.js

/** One ZIO effect, forked on a ZIO `Runtime` and awaited from Kyo.
  *
  * kyo-zio's `ZIOs.get` interrupts the ZIO fiber without waiting for it when the Kyo side is
  * interrupted, so ZIO finalizers could outlive the request. This bridge waits instead:
  *
  *   - `await` is the ordinary, interruptible wait for the Exit;
  *   - `stop` interrupts the ZIO fiber when it still runs and waits for its Exit on an
  *     uninterruptible promise, so every ZIO finalizer has finished when `stop` returns.
  *
  * Register `stop` as a Kyo `Scope.ensure` finalizer before calling `start`. Unsafe runs stay inside
  * this class.
  */
private[exts] final class ZioCall[E, A](runtime: Runtime[Any], effect: => ZIO[Any, E, A])(using AllowUnsafe):
    private val result   = Fiber.Promise.Unsafe.init[Exit[E, A], Any]()
    private val finished = Fiber.Promise.Unsafe.initUninterruptible[Exit[E, A], Any]()
    private var fiber: Option[zio.Fiber.Runtime[E, A]] = None
    private var stopped = false

    /** Forks the effect once, unless `stop` already ran. */
    def start(): Unit =
      if !stopped && fiber.isEmpty then
          Unsafe.unsafely {
            val running = runtime.unsafe.fork(ZIO.suspendSucceed(effect))
            fiber = Some(running)
            running.unsafe.addObserver { exit =>
              result.completeDiscard(Result.succeed(Kyo.lift(exit)))
              finished.completeDiscard(Result.succeed(Kyo.lift(exit)))
            }
          }

    /** Waits for the Exit. Interrupting the waiting Kyo fiber does not stop the ZIO fiber: `stop` does. */
    def await(using Frame): Exit[E, A] < Async = result.safe.get

    /** Waits for the Exit without being interruptible and without interrupting the ZIO fiber. */
    def join(using Frame): Exit[E, A] < Async = finished.safe.get

    /** Interrupts the ZIO fiber if it still runs and waits for its Exit. `Absent` when it never started. */
    def stop(using Frame): Maybe[Exit[E, A]] < Async =
      Sync.Unsafe.defer {
        stopped = true
        fiber match
            case None          => Kyo.lift(Maybe.empty[Exit[E, A]])
            case Some(running) =>
              if finished.poll().isEmpty then
                  Unsafe.unsafely(running.unsafe.interrupt(Cause.interrupt(FiberId.None)))
              finished.safe.get.map(exit => Maybe(exit))
      }
end ZioCall

private[exts] object ZioCall:
    /** Closes a ZIO scope on `runtime` and waits for its finalizers. A failing finalizer is logged:
      * Kyo scope finalizers cannot change a response that is already decided.
      */
    def closeScope(runtime: Runtime[Any], scope: zio.Scope.Closeable, exit: Exit[Any, Any])(using Frame): Unit < Async =
      Sync.Unsafe.defer {
        val close = new ZioCall(runtime, scope.close(exit))
        close.start()
        // claude 2026-10-19: changed: logs through logFinalizerFailure (issue #40)
        close.join.map {
          case Exit.Failure(cause) => Sync.defer(logFinalizerFailure(cause))
          case _                   => Kyo.unit
        }
      }

    // claude 2026-10-19: created: one log line for every failing ZIO request-scope finalizer (issue #40)
    /** Logs a failed scope close on the server, like Kyo logs a failing `Scope` finalizer. An
      * interruption alone is not a failure.
      */
    def logFinalizerFailure(cause: Cause[Any]): Unit =
      if !cause.isInterruptedOnly then js.Dynamic.global.console.error("h34s: a ZIO finalizer failed", cause.prettyPrint)
end ZioCall
