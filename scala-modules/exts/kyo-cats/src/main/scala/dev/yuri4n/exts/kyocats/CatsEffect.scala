package dev.yuri4n.exts.kyocats

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import kyo.{Abort, Async, Frame, Scope, Sync, `<`}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/** Run Cats Effect IO within a Kyo scope, keeping Cats resource ownership in IO.
  *
  * Pass the whole Resource.use program. Acquisition of the running IO and its
  * cancellation finalizer is atomic. Leaving the Kyo scope cancels the IO and
  * awaits its cleanup; normal IO completion already waits for Resource release.
  * Kyo RC7 callers must join scoped workers on interruption, as KHandler does.
  *
  * IO construction is lazy and repeated for every evaluation. IO failures use
  * Abort[Throwable] with a generic wrapper and the original exception as cause;
  * an infrastructure exception never becomes approved HTTP response data.
  */
object CatsEffect:
    def run[A](program: => IO[A])(using Frame): A < (Async & Scope & Abort[Throwable]) =
      given ExecutionContext = ExecutionContext.global
      Scope.acquireRelease(Sync.defer {
        // IO.defer also captures a throw while constructing the by-name IO.
        val (result, cancel) = IO.defer(program).unsafeToFutureCancelable()
        // Reify failed Futures, including Cats self-cancellation, so they enter
        // Abort instead of becoming Async.fromFuture panics.
        (result.transform(outcome => Success(outcome)), cancel)
      }) { case (_, cancel) =>
        Sync.defer(cancel()).map(Async.fromFuture(_))
      }.map { case (result, _) =>
        Async.fromFuture(result).map {
          case Success(value) => value
          case Failure(error) => Abort.fail(new RuntimeException("Cats Effect operation failed", error))
        }
      }
