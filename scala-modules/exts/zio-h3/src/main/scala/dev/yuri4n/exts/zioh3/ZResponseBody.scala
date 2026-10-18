package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.kyoh3.ResponseBody
import dev.yuri4n.exts.zioh3.internal.ZStreamSource
import kyo.{Frame, Sync, `<`}
import org.getshaka.nativeconverter.NativeConverter
import zio.Runtime
import zio.stream.ZStream

/** What a ZIO handler may return as its response body.
  *
  *   - any `A` with a `NativeConverter` (converted after the handler, inside the request);
  *   - `Unit` (no body: 204 unless the handler set another status; no converter needed);
  *   - a `ZStream[Any, E, String]` (`E` a `Throwable`). Each string is written as UTF-8 text, as is.
  *     h34s writes the head before the first chunk (`text/plain; charset=utf-8` unless you set a
  *     content type) and keeps the request `Scope` open until the stream ends or the client goes
  *     away. To stream values, encode each one to text yourself, for example newline-delimited JSON
  *     with `stream.map(value => value.toJson + "\n")`.
  */
trait ZResponseBody[B]:
    private[exts] def toCore(body: B, runtime: Runtime[Any])(using Frame): ResponseBody.Outgoing < Sync

    /** Whether the request scope must stay open while the body is sent. */
    private[exts] def streamed: Boolean = false

object ZResponseBody extends StreamZResponseBody:
    given unit: ZResponseBody[Unit]:
        private[exts] def toCore(body: Unit, runtime: Runtime[Any])(using Frame): ResponseBody.Outgoing < Sync =
          ResponseBody.Outgoing.Empty

    /** Streams that cannot fail, such as `ZStream("a", "b")` (type inference does not pick `Nothing`). */
    given infallibleStream: ZResponseBody[ZStream[Any, Nothing, String]] = streamBody[Nothing]

    /** The core's body type class for a ZIO body, running streams on `runtime`. */
    private[exts] def core[B](body: ZResponseBody[B], runtime: Runtime[Any]): ResponseBody[B] =
      new ResponseBody[B]:
          private[exts] def encode(value: B)(using Frame): ResponseBody.Outgoing < Sync = body.toCore(value, runtime)
end ZResponseBody

trait StreamZResponseBody extends LowPriorityZResponseBody:
    /** Node writes only strings and bytes to a response, so stream elements are strings. */
    given stream: [E <: Throwable] => ZResponseBody[ZStream[Any, E, String]] = streamBody[E]

    protected def streamBody[E <: Throwable]: ZResponseBody[ZStream[Any, E, String]] =
      streamOf[E, String](ResponseBody.text)

    /** A stream with its own element encoding. Internal (tests encode other element types). */
    private[exts] def streamOf[E <: Throwable, A](encode: A => scala.scalajs.js.Any): ZResponseBody[ZStream[Any, E, A]] =
      new ZResponseBody[ZStream[Any, E, A]]:
          override private[exts] def streamed: Boolean = true

          private[exts] def toCore(body: ZStream[Any, E, A], runtime: Runtime[Any])(using
              Frame,
          ): ResponseBody.Outgoing < Sync =
            ResponseBody.Outgoing.Streamed(ZStreamSource(runtime, body, encode))

trait LowPriorityZResponseBody:
    given native: [A: NativeConverter] => ZResponseBody[A]:
        private[exts] def toCore(body: A, runtime: Runtime[Any])(using Frame): ResponseBody.Outgoing < Sync =
          Sync.defer(ResponseBody.Outgoing.Value(summon[NativeConverter[A]].toNative(body)))
