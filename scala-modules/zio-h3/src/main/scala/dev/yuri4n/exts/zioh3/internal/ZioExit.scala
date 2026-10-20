package dev.yuri4n.exts.zioh3.internal

import dev.yuri4n.exts.h3.{ErrorEntry, SafeError}
import dev.yuri4n.exts.zioh3.ErrorResponse
import kyo.{Abort, Frame, Kyo, Result, `<`}
import zio.{Cause, Exit}

import scala.util.control.NoStackTrace

/** A ZIO failure that the client must not see. Its text is ZIO's full `Cause.prettyPrint`
  * (failures, defects, nested causes and ZIO traces with source files and lines), so logs and
  * development diagnostics keep everything ZIO knows.
  */
private[exts] final class ZioDefect(val zioCause: Cause[Any]) extends RuntimeException(zioCause.prettyPrint)
    with NoStackTrace:
    override def toString: String = getMessage

/** `ErrorResponse` as an approved error: its status and one entry per message. */
private[exts] final class ApprovedResponse(response: ErrorResponse) extends SafeError:
    def message: String                    = response.errors.head
    override def status: Int               = response.status
    override def errors: List[ErrorEntry] = response.errors.toList.map(ErrorEntry(_))

/** How a ZIO Exit reaches the Kyo core. */
private[exts] object ZioExit:
    /** The one typed failure of a cause, when it has no defect and no other failure. Interruptions
      * of sibling fibers that accompany the failure (for example from `zipPar`) do not change it.
      */
    def single[E](cause: Cause[E]): Option[E] =
      if cause.defects.nonEmpty then None
      else
          cause.failures match
              case List(error) => Some(error)
              case _           => None

    /** The error the request renderer sees. Only an explicit `SafeError` or `ErrorResponse` failure
      * is approved; every other typed failure, a defect, several failures, or interruption becomes a
      * `ZioDefect` (an opaque 500 with the full cause in diagnostics).
      */
    def requestError(cause: Cause[Throwable]): Result.Error[Throwable] =
      single(cause) match
          case Some(response: ErrorResponse) => Result.Failure(ApprovedResponse(response))
          case Some(safe: SafeError)         => Result.Failure(safe)
          case Some(_)                       => Result.Failure(ZioDefect(cause))
          case None                          => Result.Panic(ZioDefect(cause))

    def request[A](exit: Exit[Throwable, A])(using Frame): A < Abort[Throwable] =
      exit match
          case Exit.Success(value) => Kyo.lift(value)
          case Exit.Failure(cause) => Abort.error(requestError(cause))

    /** Keeps a single typed failure as is (for application resources such as `ZShared`). */
    def typed[E <: Throwable, A](exit: Exit[E, A])(using Frame): A < Abort[Throwable] =
      exit match
          case Exit.Success(value) => Kyo.lift(value)
          case Exit.Failure(cause) =>
            single(cause) match
                case Some(error) => Abort.fail(error)
                case None        => Abort.panic(ZioDefect(cause))
end ZioExit
