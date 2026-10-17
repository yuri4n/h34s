package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.h3.internal.Nitro
import dev.yuri4n.exts.kyoh3.internal.Interop
import kyo.{Abort, AllowUnsafe, Async, Fiber, Frame, Kyo, Result, Scope, Sync, `<`}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** An application-scoped resource, such as a database client.
  *
  * The first `get` acquires it; concurrent first calls share one acquisition. All requests then
  * share the value. A failed acquisition is not cached: the next `get` tries again. The resource
  * is released by `close`, or when Nitro closes (the h34s Nitro plugin calls every registered
  * `Shared`).
  *
  * The acquisition runs in its own scope, on a fiber that no request owns, so cancelling one
  * request does not cancel an acquisition that other requests wait for.
  */
final class Shared[A] private[kyoh3] (acquire: => A < (Async & Scope & Abort[Throwable]))(using Frame):
    import Shared.*

    private val state = new AtomicReference[State[A]](State.Idle())

    def get(using Frame): A < (Async & Abort[Throwable]) =
      Sync.Unsafe.defer {
        state.get match
            case State.Ready(value, _, _) => Kyo.lift(value)
            case State.Acquiring(holder)  => holder.ready.safe.get
            case idle @ State.Idle()      =>
              val acquiring: State.Acquiring[A] = new State.Acquiring(new Holder[A])
              if state.compareAndSet(idle, acquiring) then
                  start(acquiring)
                  acquiring.holder.ready.safe.get
              else get
      }

    /** Releases the resource and waits for its finalizers. Later `get` calls acquire it again. */
    def close(using Frame): Unit < Async =
      Sync.Unsafe.defer {
        state.getAndSet(State.Idle()) match
            case State.Ready(_, holder, unregister) =>
              unregister()
              release(holder)
            case State.Acquiring(holder)            => release(holder)
            case State.Idle()                       => Kyo.unit
      }

    private def release(holder: Holder[A])(using Frame, AllowUnsafe): Unit < Async =
      holder.release.completeDiscard(Result.succeed(()))
      holder.done.safe.get

    private def start(acquiring: State.Acquiring[A])(using AllowUnsafe): Unit =
      val holder = acquiring.holder
      val program: Unit < Async =
        Abort.run[Any](
          Scope.run(
            acquire.map { value =>
              Sync.Unsafe.defer {
                ready(acquiring, value)
                holder.ready.completeDiscard(Result.succeed(Kyo.lift(value)))
              }.andThen(holder.release.safe.get)
            },
          ),
        ).map(result => Interop.typed(result).foldError(
          _ => Kyo.unit,
          failure =>
            Sync.Unsafe.defer {
              state.compareAndSet(acquiring, State.Idle())
              holder.ready.completeDiscard(failure)
            },
        ))
      Fiber.Unsafe.init(program).onComplete(_ => holder.done.completeDiscard(Result.succeed(())))

    private def ready(acquiring: State.Acquiring[A], value: A): Unit =
      if state.get eq acquiring then
          val unregister = registerClose()
          if !state.compareAndSet(acquiring, State.Ready(value, acquiring.holder, unregister)) then unregister()

    /** Registers this resource with the Nitro close hook, when the h34s plugin is installed. */
    private def registerClose(): () => Unit =
      Nitro.global.filter(record => Nitro.isFunction(record.asInstanceOf[js.Dynamic].onClose)) match
          case None         => () => ()
          case Some(record) =>
            val closeOnShutdown: js.Function0[js.Any] = () =>
              import AllowUnsafe.embrace.danger
              given ExecutionContext = ExecutionContext.global
              Fiber.Unsafe.init(close).toFuture().toJSPromise
            val unregister = record.onClose(closeOnShutdown)
            () => if Nitro.isFunction(unregister) then unregister()
end Shared

object Shared:
    /** Declares a shared resource. Nothing is acquired until the first `get`. */
    def apply[A](acquire: => A < (Async & Scope & Abort[Throwable]))(using Frame): Shared[A] =
      new Shared(acquire)

    // Uninterruptible: a request that stops waiting must not interrupt an acquisition or a
    // release that other requests share.
    private final class Holder[A](using AllowUnsafe):
        val ready   = Fiber.Promise.Unsafe.initUninterruptible[A, Abort[Throwable]]()
        val release = Fiber.Promise.Unsafe.initUninterruptible[Unit, Any]()
        val done    = Fiber.Promise.Unsafe.initUninterruptible[Unit, Any]()

    private enum State[A]:
        case Idle()
        case Acquiring(holder: Holder[A])
        case Ready(value: A, holder: Holder[A], unregister: () => Unit)
end Shared
