package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.h3.WireShape
import dev.yuri4n.exts.h3.internal.BodyShape
import kyo.{Abort, Async, Frame, Scope, Stream, Sync, Tag, `<`}
import org.getshaka.nativeconverter.NativeConverter

import scala.scalajs.js

/** What a handler may return as its response body.
  *
  *   - any `A` with a `NativeConverter` (converted after the handler, inside the request scope);
  *   - `Unit` (no body: 204 unless the handler set another status; no converter needed);
  *   - a `kyo.Stream[String, S]`, where `S` needs at most `Async & Abort[Throwable] & Scope`. Each
  *     string is written as UTF-8 text, as is. The core writes the head before the first chunk
  *     (`text/plain; charset=utf-8` unless you set a content type) and keeps the request scope open
  *     until the stream ends. To stream values, encode each one to text yourself, for example
  *     newline-delimited JSON with `stream.map(value => value.toJson + "\n")` and
  *     `Response.header("content-type", "application/x-ndjson")`.
  */
trait ResponseBody[B]:
    private[exts] def encode(body: B)(using Frame): ResponseBody.Outgoing < Sync

    /** What the body looks like on the wire, for the route's generated TypeScript declaration. */
    private[exts] def shape: BodyShape

object ResponseBody extends LowPriorityResponseBody:
    type StreamEffects = Async & Abort[Throwable] & Scope

    /** The encoded body, ready for the transport. Internal. */
    private[exts] enum Outgoing:
        case Value(native: js.Any)
        case Empty
        case Streamed(chunks: Stream[js.Any, StreamEffects])

    given unit: ResponseBody[Unit]:
        private[exts] def encode(body: Unit)(using Frame): Outgoing < Sync = Outgoing.Empty
        private[exts] def shape: BodyShape                                  = BodyShape.Empty

    /** Node writes only strings and bytes to a response, so stream elements are strings. */
    given stream: [S >: StreamEffects] => ResponseBody[Stream[String, S]]:
        private[exts] def encode(body: Stream[String, S])(using Frame): Outgoing < Sync =
          Outgoing.Streamed(chunks(body)(text))

        private[exts] def shape: BodyShape = BodyShape.Text

    /** A null element would fail the response write; fail the stream instead (a defect). */
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
    /** `WireShape` finds the TypeScript type of what the converter writes; every type has one. */
    given native: [A: {NativeConverter, WireShape}] => ResponseBody[A]:
        private[exts] def encode(body: A)(using Frame): ResponseBody.Outgoing < Sync =
          Sync.defer(ResponseBody.Outgoing.Value(summon[NativeConverter[A]].toNative(body)))

        private[exts] def shape: BodyShape = BodyShape.Value(() => summon[WireShape[A]].of(summon[NativeConverter[A]]))
