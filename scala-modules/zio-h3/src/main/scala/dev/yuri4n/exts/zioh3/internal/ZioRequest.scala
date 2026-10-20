package dev.yuri4n.exts.zioh3.internal

import dev.yuri4n.exts.h3.ResponseHead
import dev.yuri4n.exts.kyoh3.{HandlerEffects, HandlerTransport}
import dev.yuri4n.exts.zioh3.HandlerEnv
import kyo.{Env, Frame, Scope, Sync, Var, `<`}
import zio.{Exit, Runtime, UIO, Unsafe, ZEnvironment, ZIO}

// claude 2026-10-19: changed: value, Unit and interrupted programs log a failed close and keep their Exit (issue #40)
// claude 2026-10-19: changed: no streamed flag: a stream is sent inside the program (ZHandler.sendStream), so the fiber always closes the request scope itself; the transport joins the ZIO environment
/** One ZIO handler program inside the Kyo request worker.
  *
  * The program runs on one ZIO fiber with its own request `Scope`, `ZState[ResponseHead]` and the
  * request's `HandlerTransport`:
  *
  *   - the fiber closes the request scope itself after the program; a failed close is logged and
  *     does not change the program's Exit, as in Kyo. A stream sent with `Response.sendStream` has
  *     ended or was cancelled by then, because the program waits for it;
  *   - interruption (client disconnect): the Kyo finalizer interrupts the fiber and waits until the
  *     request scope is closed, so ZIO finalizers finish before the core settles the response; a
  *     failed close is logged and the fiber's Exit stays the interruption.
  *
  * The head is copied into the core's `Var[ResponseHead]` before the Exit is re-raised, so the
  * failure renderer sees what the program set.
  */
private[exts] object ZioRequest:
    final case class Completed[+B](head: ResponseHead, exit: Exit[Throwable, B])

    /** `afterProgram` runs inside the fiber's uninterruptible region, after the program returned
      * and before the fiber closes the scope (a test seam for interrupts that arrive in that window).
      */
    def run[B](runtime: Runtime[Any], afterProgram: UIO[Unit] = ZIO.unit)(
        program: => ZIO[HandlerEnv, Throwable, B],
    )(using Frame): B < HandlerEffects =
      Env.use[HandlerTransport](transport => run(runtime, transport, afterProgram)(program))

    private def run[B](runtime: Runtime[Any], transport: HandlerTransport, afterProgram: UIO[Unit])(
        program: => ZIO[HandlerEnv, Throwable, B],
    )(using Frame): B < HandlerEffects =
      Sync.Unsafe.defer {
        // The Kyo side owns the ZIO request scope. An interrupt that arrives after the program
        // returned replaces the fiber's Exit, so the scope must not travel inside that Exit.
        val scope = Unsafe.unsafely(zio.Scope.unsafe.make)
        val call  = new ZioCall(runtime, fiber(scope, transport, afterProgram, program))
        // Closing a scope that the fiber already closed is a no-op.
        Scope.ensure(call.stop.andThen(ZioCall.closeScope(runtime, scope, Exit.unit)))
          .andThen(Sync.Unsafe.defer(call.start()))
          .andThen(call.await)
          .map {
            case Exit.Success(completed) =>
              Var.setDiscard(completed.head).andThen(ZioExit.request(completed.exit))
            case Exit.Failure(cause)     =>
              // The fiber itself can only be interrupted (by `stop`).
              ZioExit.request(Exit.failCause(cause))
          }
      }

    private def fiber[B](
        scope: zio.Scope.Closeable,
        transport: HandlerTransport,
        afterProgram: UIO[Unit],
        program: => ZIO[HandlerEnv, Throwable, B],
    ): UIO[Completed[B]] =
      ZIO.uninterruptibleMask { restore =>
        restore(stateful(scope, transport, program)).exit.flatMap {
          // claude 2026-10-19: changed: a failing finalizer is logged and keeps the program's Exit (issue #40)
          case Exit.Success((exit, head)) =>
            afterProgram *> closeAndLog(scope, exit).as(Completed(head, exit))
          // claude 2026-10-19: changed: a failing finalizer during an interruption is logged too (issue #40 review)
          case Exit.Failure(cause)        =>
            closeAndLog(scope, Exit.failCause(cause)).as(Completed(ResponseHead.empty, Exit.failCause(cause)))
        }
      }

    // claude 2026-10-19: created: closes the request scope and logs a failed close, interruptions included (issue #40 review)
    /** Closes `scope` and logs a failed close. Never fails: the log runs outside the fiber's
      * failure path, so a cause that cannot be rendered cannot turn the response into a 500.
      */
    private def closeAndLog(scope: zio.Scope.Closeable, exit: Exit[Any, Any]): UIO[Unit] =
      scope.close(exit).exit.flatMap { closed =>
        ZIO.attempt(closed.causeOption.foreach(ZioCall.logFinalizerFailure(_, includeInterrupts = true))).ignore
      }

    private def stateful[B](
        scope: zio.Scope,
        transport: HandlerTransport,
        program: => ZIO[HandlerEnv, Throwable, B],
    ): UIO[(Exit[Throwable, B], ResponseHead)] =
      ZIO.stateful[zio.Scope & HandlerTransport](ResponseHead.empty)(
        // A program that throws while it is built is a defect, never an approved error.
        ZIO.suspendSucceed(program).exit.zip(ZIO.getState[ResponseHead]),
      ).provideEnvironment(ZEnvironment[zio.Scope, HandlerTransport](scope, transport))
end ZioRequest
