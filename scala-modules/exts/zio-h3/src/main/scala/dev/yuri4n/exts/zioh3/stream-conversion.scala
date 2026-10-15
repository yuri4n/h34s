package dev.yuri4n.exts.zioh3

import org.getshaka.nativeconverter.NativeConverter
import org.scalajs.dom
import zio.{CancelableFuture, Chunk, Exit, Promise, Runtime, Scope, Task, UIO, Unsafe, ZEnvironment, ZIO}

import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** A pull-driven Web Stream. The stream scope stays open until EOF, failure, or awaited cancellation.
  * One upstream chunk is requested per pull; memory use therefore depends on the upstream chunk size.
  */
def taskStreamToReadableStream[A: NativeConverter](
    stream: TaskStream[A],
    runtime: Runtime[Any] = Runtime.default,
)(using ec: ExecutionContext): dom.ReadableStream[js.Any] =
    val (scope, closed) = Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe.run(Scope.make.zip(Promise.make[Nothing, Exit[Nothing, Unit]])).getOrThrowFiberFailure()
    }
    var closeStarted = false
    var cancelled = false
    var active: Option[CancelableFuture[Unit]] = None
    var next: ZIO[Any, Option[Throwable], Chunk[A]] = ZIO.fail(None)

    def close(exit: Exit[Any, Any]): UIO[Unit] = ZIO.uninterruptible {
      ZIO.suspendSucceed {
        if closeStarted then closed.await.flatMap(ZIO.done(_))
        else
          closeStarted = true
          scope.close(exit).exit.flatMap(result => closed.succeed(result) *> ZIO.done(result))
      }
    }

    def run(effect: Task[Unit]): js.Promise[Unit] = Unsafe.unsafe { implicit unsafe =>
      val future = runtime.unsafe.runToFuture(effect.onError(cause => close(Exit.failCause(cause))))
      active = Some(future)
      future.toJSPromise
    }

    val source = js.Dynamic.literal(
      start = js.Any.fromFunction1 { (_: dom.ReadableStreamController[js.Any]) =>
        run(stream.toPull.provideEnvironment(ZEnvironment(scope)).map(pull => next = pull))
      },
      pull = js.Any.fromFunction1 { (controller: dom.ReadableStreamController[js.Any]) =>
        run(next.foldZIO(
          {
            case None => close(Exit.unit) *> ZIO.attempt { if !cancelled then controller.close() }
            case Some(error) => ZIO.fail(error)
          },
          chunk => ZIO.attempt {
            if !cancelled then chunk.foreach(value => controller.enqueue(summon[NativeConverter[A]].toNative(value)))
          },
        ))
      },
      cancel = js.Any.fromFunction1 { (_: js.Any) =>
        cancelled = true
        val interrupted = active.fold(scala.concurrent.Future.successful(()))(_.cancel().map(_ => ()))
        interrupted.flatMap { _ =>
          Unsafe.unsafe { implicit unsafe => runtime.unsafe.runToFuture(close(Exit.unit)) }
        }.toJSPromise
      },
    ).asInstanceOf[dom.ReadableStreamUnderlyingSource[js.Any]]

    dom.ReadableStream[js.Any](underlyingSource = source)
end taskStreamToReadableStream

/** Reader convenience API; HTTP transports must receive the stream itself. */
def taskStreamToReadableStreamReader[A: NativeConverter](
    stream: TaskStream[A],
    runtime: Runtime[Any] = Runtime.default,
)(using ec: ExecutionContext): dom.ReadableStreamReader[js.Any] =
    taskStreamToReadableStream(stream, runtime).getReader()

object streamconversion:
    extension [A: NativeConverter](zstream: TaskStream[A])
      def toReadableStream(
          runtime: Runtime[Any] = Runtime.default,
      )(using ec: ExecutionContext): dom.ReadableStream[js.Any] =
        taskStreamToReadableStream(zstream, runtime)

      def toReadableStreamReader(
          runtime: Runtime[Any] = Runtime.default,
      )(using ec: ExecutionContext): dom.ReadableStreamReader[js.Any] =
        taskStreamToReadableStreamReader(zstream, runtime)
