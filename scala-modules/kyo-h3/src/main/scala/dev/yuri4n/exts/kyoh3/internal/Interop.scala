package dev.yuri4n.exts.kyoh3.internal

import kyo.{Abort, Async, Fiber, Frame, Kyo, Result, Sync, `<`}

import scala.scalajs.js

/** Promise bridges between JavaScript and Kyo. Unsafe runs stay inside this boundary. */
private[exts] object Interop:
    /** Awaits a JavaScript Promise. A rejection is a typed failure; a synchronous throw too. */
    def await[A](promise: => js.Promise[A])(using Frame): A < (Async & Abort[Throwable]) =
      Sync.Unsafe.defer {
        val completion = Fiber.Promise.Unsafe.init[A, Abort[Throwable]]()
        try
            promise.`then`[Unit](
              value => completion.completeDiscard(Result.succeed(Kyo.lift(value))),
              error => completion.completeDiscard(Result.fail(throwable(error))),
            )
        catch case error: Throwable => completion.completeDiscard(Result.fail(error))
        completion.safe.get
      }

    /** Awaits a callback-style operation that calls `register`'s argument exactly once. */
    def async[E, A](register: (Result[E, A] => Unit) => Unit)(using Frame): A < (Async & Abort[E]) =
      Sync.Unsafe.defer {
        val completion = Fiber.Promise.Unsafe.init[A, Abort[E]]()
        register(result => completion.completeDiscard(result.map(Kyo.lift)))
        completion.safe.get
      }

    /** Narrows a result of `Abort.run[Any]`. Kyo's `Abort.run[Throwable]` would classify every thrown
      * exception as a typed failure; running with `Any` keeps thrown exceptions as panics, so only an
      * explicit `Abort.fail` can approve a `SafeError` for the client.
      */
    def typed[A](result: Result[Any, A]): Result[Throwable, A] =
      result.foldError(
        value => Result.succeed(value),
        {
          case failure: Result.Failure[?] =>
            failure.failure.asInstanceOf[Matchable] match
                case error: Throwable => Result.fail(error)
                case other            => Result.panic(IllegalStateException(s"Non-throwable failure: ${other.getClass.getName}"))
          case panic: Result.Panic        => panic
        },
      )

    def throwable(error: Any): Throwable = error.asInstanceOf[Matchable] match
        case throwable: Throwable => throwable
        case other                => js.JavaScriptException(other)
end Interop
