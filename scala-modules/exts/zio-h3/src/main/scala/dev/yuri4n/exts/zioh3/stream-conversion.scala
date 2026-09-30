package dev.yuri4n.exts.zioh3

import org.getshaka.nativeconverter.NativeConverter
import org.scalajs.dom
import org.scalajs.dom.ReadableStreamController
import zio.{CancelableFuture, Runtime, Task, Unsafe, ZIO}

import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

/** Converts a TaskStream into a ReadableStreamReader.
  *
  * This function bridges ZIO Streams and the Web Streams API, allowing you to consume a ZIO Stream
  * as a ReadableStreamReader in JavaScript/TypeScript code.
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

    dom.ReadableStream[js.Any](underlyingSource = underlyingSource).getReader()
end taskStreamToReadableStreamReader

object streamconversion:
    extension [A: NativeConverter](zstream: TaskStream[A])
      def toReadableStreamReader(
          runtime: Runtime[Any] = Runtime.default,
      )(using ec: ExecutionContext): dom.ReadableStreamReader[js.Any] =
        taskStreamToReadableStreamReader(zstream, runtime)
