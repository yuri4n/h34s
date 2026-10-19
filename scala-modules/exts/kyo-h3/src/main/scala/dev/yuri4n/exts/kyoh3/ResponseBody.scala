package dev.yuri4n.exts.kyoh3

import kyo.{Abort, Async, Frame, Scope, Stream, Sync, Tag, `<`}
import org.getshaka.nativeconverter.NativeConverter

import scala.annotation.implicitNotFound
import scala.scalajs.js

// claude 2026-10-19: changed: streams are sent with Response.sendStream, not returned; Sent is a body; a missing instance names the three options
/** What a handler may return as its response body.
  *
  *   - any `A` with a `NativeConverter` (converted after the handler, inside the request scope);
  *   - `Unit` (no body: 204 unless the handler set another status; no converter needed);
  *   - `Sent`, the result of `Response.sendStream(stream)`: the handler sent a stream itself.
  *
  * A `kyo.Stream` is not a body: send it with `Response.sendStream` as the handler's last step.
  */
@implicitNotFound(
  "h34s cannot send ${B} as a response body. Return a value with a NativeConverter, return Unit for a 204, or end the handler with Response.sendStream(stream) to stream text",
)
trait ResponseBody[B]:
    private[exts] def encode(body: B)(using Frame): ResponseBody.Outgoing < Sync

object ResponseBody extends LowPriorityResponseBody:
    type StreamEffects = Async & Abort[Throwable] & Scope

    /** The encoded body, ready for the transport. Internal. */
    private[exts] enum Outgoing:
        case Value(native: js.Any)
        case Empty
        // claude 2026-10-19: changed: kept only for the ZIO adapter's returned ZStream until it moves to ZHandler.sendStream
        case Streamed(chunks: Stream[js.Any, StreamEffects])
        // claude 2026-10-19: created: the program sent its response itself
        case Sent(sent: dev.yuri4n.exts.h3.Sent)

    given unit: ResponseBody[Unit]:
        private[exts] def encode(body: Unit)(using Frame): Outgoing < Sync = Outgoing.Empty

    // claude 2026-10-19: created: the body of a handler that ends with Response.sendStream (replaces the stream given)
    given sent: ResponseBody[dev.yuri4n.exts.h3.Sent]:
        private[exts] def encode(body: dev.yuri4n.exts.h3.Sent)(using Frame): Outgoing < Sync = Outgoing.Sent(body)

    /** Node writes only strings and bytes to a response, so stream elements are strings. A null
      * element would fail the response write; fail the stream instead (a defect).
      */
    private[exts] def text(element: String): js.Any =
      if element == null then throw NullPointerException("A streamed response element is null") else element

    /** The stream with each element encoded. Encoding runs in the stream's producer, so a throwing
      * encoder fails the stream and closes its scope. Internal (tests encode other element types).
      */
    private[exts] def chunks[A: Tag, S >: StreamEffects](body: Stream[A, S])(encode: A => js.Any)(using
        Frame,
    ): Stream[js.Any, StreamEffects] =
      body.map(element => Sync.defer(encode(element)))
end ResponseBody

trait LowPriorityResponseBody:
    given native: [A: NativeConverter] => ResponseBody[A]:
        private[exts] def encode(body: A)(using Frame): ResponseBody.Outgoing < Sync =
          Sync.defer(ResponseBody.Outgoing.Value(summon[NativeConverter[A]].toNative(body)))
