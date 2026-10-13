package dev.yuri4n.exts.zioh3

import org.getshaka.nativeconverter.NativeConverter
import org.scalajs.dom
import org.scalajs.dom.ReadableStreamController
import zio.{CancelableFuture, Runtime, Task, Unsafe, ZIO}

import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** Converts a TaskStream into a Web `ReadableStream`.
  *
  * Created by Claude Code on 2026-10-13: the Web stream form; the reader function below delegates
  * to it. Use this form for a transport that pipes the stream, such as h3's `sendStream`.
  *
  * Each element is converted to its native JS representation via NativeConverter and then
  * enqueued into the `ReadableStream`. The source stream runs eagerly inside the `start` callback.
  * It does not wait for `pull` requests, so Web Streams backpressure does not apply, and converted
  * chunks wait in the stream's queue until the consumer reads them. A failure in the source stream
  * rejects the `start` promise, which puts the `ReadableStream` into the errored state. Cancelling
  * the `ReadableStream` interrupts the ZIO fiber that runs the source stream; a finite in-memory
  * source has usually finished before `cancel` can run. h3 1.15 `sendStream` never cancels it, so
  * `SH3Event.sendStream` interrupts the source itself when the Node response closes; see its
  * documentation.
  *
  * When the result goes to a Node HTTP response, each native chunk must be a value that
  * `response.write` accepts: a string, a `Buffer`, or a `Uint8Array`. Objects are not encoded.
  * For Nitro's in-process fetch, which SSR `useFetch` and `$fetch` use, h3 does not pipe the
  * stream. It hands the stream to `new Response(...)`, where only `Uint8Array` chunks are readable.
  *
  * @param stream
  *   The ZIO Stream to convert
  * @param runtime
  *   The ZIO Runtime to use for executing the stream (defaults to Runtime.default)
  * @param ec
  *   ExecutionContext for async operations
  * @tparam A
  *   The type of elements in the stream (must have a NativeConverter instance)
  * @return
  *   A Web `ReadableStream` that yields native-converted elements from the stream
  */
def taskStreamToReadableStream[A: NativeConverter](
    stream: TaskStream[A],
    runtime: Runtime[Any] = Runtime.default,
)(using ec: ExecutionContext): dom.ReadableStream[js.Any] =

    var cancelFutureVar: Option[CancelableFuture[Unit]] = None

    val underlyingSource = js.Dynamic
      .literal(
        start = js.Any.fromFunction1 { (controller: ReadableStreamController[js.Any]) =>
            val effect: Task[Unit] = stream
              .foreach { (chunk: A) =>
                ZIO.attempt {
                  controller
                    .enqueue(summon[NativeConverter[A]].toNative(chunk).asInstanceOf[js.Any])
                }
              }
              .flatMap { _ =>
                ZIO.attempt {
                  controller.close()
                  ()
                }
              }

            Unsafe.unsafe { implicit unsafe =>
                val future = runtime.unsafe.runToFuture(effect)
                cancelFutureVar = Some(future)
                future.toJSPromise
            }
        },
        cancel = js.Any.fromFunction1 { (_: js.Dynamic) =>
            cancelFutureVar.foreach(_.cancel())
            ()
        },
      )
      .asInstanceOf[dom.ReadableStreamUnderlyingSource[js.Any]]

    dom.ReadableStream[js.Any](underlyingSource = underlyingSource)
end taskStreamToReadableStream

/** Converts a TaskStream into a ReadableStreamReader.
  *
  * Changed by Claude Code on 2026-10-13: delegates to `taskStreamToReadableStream` and returns
  * that stream's reader. Behavior is unchanged.
  *
  * This function bridges ZIO Streams and the Web Streams API, allowing you to consume a ZIO Stream
  * as a ReadableStreamReader in JavaScript/TypeScript code. Use `taskStreamToReadableStream` when
  * you hand the stream to a transport such as h3.
  *
  * Each element is converted to its native JS representation via NativeConverter before being
  * enqueued into the ReadableStream. The returned reader yields js.Any values that can be decoded
  * back using fromNative.
  *
  * @param stream
  *   The ZIO Stream to convert
  * @param runtime
  *   The ZIO Runtime to use for executing the stream (defaults to Runtime.default)
  * @param ec
  *   ExecutionContext for async operations
  * @tparam A
  *   The type of elements in the stream (must have a NativeConverter instance)
  * @return
  *   A ReadableStreamReader that yields native-converted elements from the stream
  */
def taskStreamToReadableStreamReader[A: NativeConverter](
    stream: TaskStream[A],
    runtime: Runtime[Any] = Runtime.default,
)(using ec: ExecutionContext): dom.ReadableStreamReader[js.Any] =
  taskStreamToReadableStream(stream, runtime).getReader()

object streamconversion:
    extension [A: NativeConverter](zstream: TaskStream[A])
      // Created by Claude Code on 2026-10-13: the stream form, for transports that pipe it.
      def toReadableStream(
          runtime: Runtime[Any] = Runtime.default,
      )(using ec: ExecutionContext): dom.ReadableStream[js.Any] =
        taskStreamToReadableStream(zstream, runtime)

      def toReadableStreamReader(
          runtime: Runtime[Any] = Runtime.default,
      )(using ec: ExecutionContext): dom.ReadableStreamReader[js.Any] =
        taskStreamToReadableStreamReader(zstream, runtime)
