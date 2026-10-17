package dev.yuri4n.exts.zioh3.internal

import dev.yuri4n.exts.kyoh3.ResponseBody
import kyo.{Abort, AllowUnsafe, Async, Chunk, Emit, Frame, Kyo, Loop, Maybe, Scope, Stream, Sync, `<`}
import zio.stream.ZStream
import zio.{Cause, Exit, Runtime, Unsafe, ZEnvironment, ZIO}

import scala.scalajs.js

/** A ZStream as the Kyo stream that the core sends.
  *
  * kyo-zio's `ZStreams.get` is not used: it closes the ZIO stream scope from an inner `Scope.run` and
  * pulls with `ZIOs.get`, so a cancelled stream would not wait for its ZIO finalizers.
  *
  * Here the stream runs inside the core's producer fiber. Each chunk is one awaited ZIO pull. One
  * finalizer in the producer's scope stops an in-flight pull, then closes the ZIO stream scope and
  * waits for its finalizers, so cancelling the Web stream waits for them too.
  */
private[exts] object ZStreamSource:
    def apply[E <: Throwable, A](runtime: Runtime[Any], stream: => ZStream[Any, E, A], encode: A => js.Any)(using
        Frame,
    ): Stream[js.Any, ResponseBody.StreamEffects] =
      Stream {
        Sync.Unsafe.defer {
          val puller = new Puller(runtime, stream)
          Scope.ensure(puller.close).andThen(
            Loop.foreach {
              puller.next.map {
                case Maybe.Present(chunk) if chunk.isEmpty => Loop.continue
                case Maybe.Present(chunk)                  =>
                  // Conversion runs in the producer: a throwing converter fails the stream and
                  // closes its scope.
                  Sync.defer(Chunk.from(chunk.map(encode))).map(encoded => Emit.valueWith(encoded)(Loop.continue))
                case _                                     => Loop.done
              }
            },
          )
        }
      }

    private final class Puller[E <: Throwable, A](runtime: Runtime[Any], stream: => ZStream[Any, E, A])(using
        AllowUnsafe,
    ):
        private val scope                                                = Unsafe.unsafely(zio.Scope.unsafe.make)
        private var pull: Option[ZIO[Any, Option[E], zio.Chunk[A]]]      = None
        private var current: Option[ZioCall[Option[E], zio.Chunk[A]]]    = None

        private def step: ZIO[Any, Option[E], zio.Chunk[A]] =
          ZIO.suspendSucceed {
            pull match
                case Some(next) => next
                case None       =>
                  stream.toPull.provideEnvironment(ZEnvironment[zio.Scope](scope)).flatMap { next =>
                    ZIO.succeed { pull = Some(next) } *> next
                  }
          }

        /** The next chunk; `Absent` at the end of the stream. */
        def next(using Frame): Maybe[zio.Chunk[A]] < (Async & Abort[Throwable]) =
          Sync.Unsafe.defer {
            val call = new ZioCall(runtime, step)
            current = Some(call)
            call.start()
            call.await
          }.map {
            case Exit.Success(chunk) => Kyo.lift(Maybe(chunk))
            case Exit.Failure(cause) =>
              Cause.flipCauseOption(cause) match
                  case None          => Kyo.lift(Maybe.empty[zio.Chunk[A]])
                  case Some(failure) => Abort.error(ZioExit.requestError(failure))
          }

        /** Stops an in-flight pull, then closes the stream scope. Both are awaited. */
        def close(using Frame): Unit < Async =
          Sync.Unsafe.defer(current)
            .map(_.fold(Kyo.unit)(_.stop.unit))
            .andThen(ZioCall.closeScope(runtime, scope, Exit.unit))
    end Puller
end ZStreamSource
