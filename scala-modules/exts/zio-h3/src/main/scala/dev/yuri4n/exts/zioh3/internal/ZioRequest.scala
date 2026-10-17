package dev.yuri4n.exts.zioh3.internal

import dev.yuri4n.exts.h3.ResponseHead
import dev.yuri4n.exts.kyoh3.HandlerEffects
import dev.yuri4n.exts.zioh3.HandlerEnv
import kyo.{Frame, Kyo, Maybe, Scope, Sync, Var, `<`}
import zio.{Exit, Runtime, UIO, ZEnvironment, ZIO}

/** One ZIO handler program inside the Kyo request worker.
  *
  * The program runs on one ZIO fiber with its own request `Scope` and `ZState[ResponseHead]`:
  *
  *   - value and Unit bodies: the fiber closes the request scope itself, so a dying finalizer turns
  *     the response into a 500 (it surfaces in the Exit);
  *   - stream bodies: the scope stays open while the core sends the stream; a Kyo request finalizer
  *     closes it after the stream ended or was cancelled;
  *   - interruption (client disconnect): the Kyo finalizer interrupts the fiber and waits until the
  *     request scope is closed, so ZIO finalizers finish before the core settles the response.
  *
  * The head is copied into the core's `Var[ResponseHead]` before the Exit is re-raised, so the
  * failure renderer sees what the program set.
  */
private[exts] object ZioRequest:
    final case class Completed[+B](head: ResponseHead, exit: Exit[Throwable, B], kept: Option[zio.Scope.Closeable])

    def run[B](runtime: Runtime[Any], streamed: Boolean)(program: => ZIO[HandlerEnv, Throwable, B])(using
        Frame,
    ): B < HandlerEffects =
      Sync.Unsafe.defer {
        val call = new ZioCall(runtime, fiber(streamed, program))
        Scope.ensure(release(runtime, call))
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

    private def release[B](runtime: Runtime[Any], call: ZioCall[Nothing, Completed[B]])(using Frame) =
      call.stop.map {
        case Maybe.Present(Exit.Success(Completed(_, _, Some(scope)))) => ZioCall.closeScope(runtime, scope, Exit.unit)
        case _                                                         => Kyo.unit
      }

    private def fiber[B](streamed: Boolean, program: => ZIO[HandlerEnv, Throwable, B]): UIO[Completed[B]] =
      ZIO.uninterruptibleMask { restore =>
        zio.Scope.make.flatMap { scope =>
          restore(stateful(scope, program)).exit.flatMap {
            case Exit.Success((exit, head)) if streamed && exit.isSuccess =>
              ZIO.succeed(Completed(head, exit, Some(scope)))
            case Exit.Success((exit, head))                               =>
              scope.close(exit).exit.map(closed => Completed(head, afterClose(exit, closed), None))
            case Exit.Failure(cause)                                      =>
              scope.close(Exit.failCause(cause)).as(Completed(ResponseHead.empty, Exit.failCause(cause), None))
          }
        }
      }

    private def stateful[B](
        scope: zio.Scope,
        program: => ZIO[HandlerEnv, Throwable, B],
    ): UIO[(Exit[Throwable, B], ResponseHead)] =
      ZIO.stateful[zio.Scope](ResponseHead.empty)(
        // A program that throws while it is built is a defect, never an approved error.
        ZIO.suspendSucceed(program).exit.zip(ZIO.getState[ResponseHead]),
      ).provideEnvironment(ZEnvironment[zio.Scope](scope))

    /** A finalizer that dies makes the request fail, also after a success or a safe failure. */
    private def afterClose[B](exit: Exit[Throwable, B], closed: Exit[Nothing, Unit]): Exit[Throwable, B] =
      (exit, closed) match
          case (_, Exit.Success(_))                         => exit
          case (Exit.Success(_), Exit.Failure(closeCause))  => Exit.failCause(closeCause)
          case (Exit.Failure(cause), Exit.Failure(closeCause)) => Exit.failCause(cause ++ closeCause)
end ZioRequest
