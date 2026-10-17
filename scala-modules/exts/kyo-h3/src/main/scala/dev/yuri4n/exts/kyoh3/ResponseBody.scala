package dev.yuri4n.exts.kyoh3

import kyo.{Abort, Async, Frame, Scope, Stream, Sync, Tag, `<`}
import org.getshaka.nativeconverter.NativeConverter

import scala.scalajs.js

/** What a handler may return as its response body.
  *
  *   - any `A` with a `NativeConverter` (converted after the handler, inside the request scope);
  *   - `Unit` (no body: 204 unless the handler set another status; no converter needed);
  *   - a `kyo.Stream[A, S]` with a `NativeConverter[A]`, where `S` needs at most
  *     `Async & Abort[Throwable] & Scope`. The core writes the head before the first chunk and keeps
  *     the request scope open until the stream ends.
  */
trait ResponseBody[B]:
    private[exts] def encode(body: B)(using Frame): ResponseBody.Outgoing < Sync

object ResponseBody extends LowPriorityResponseBody:
    type StreamEffects = Async & Abort[Throwable] & Scope

    /** The encoded body, ready for the transport. Internal. */
    private[exts] enum Outgoing:
        case Value(native: js.Any)
        case Empty
        case Streamed(chunks: Stream[js.Any, StreamEffects])

    given unit: ResponseBody[Unit]:
        private[exts] def encode(body: Unit)(using Frame): Outgoing < Sync = Outgoing.Empty

    given stream: [A: {NativeConverter, Tag}, S >: StreamEffects] => ResponseBody[Stream[A, S]]:
        private[exts] def encode(body: Stream[A, S])(using Frame): Outgoing < Sync =
          // Element conversion runs in the stream's producer, so a throwing converter fails the
          // stream and closes its scope.
          Outgoing.Streamed(body.map(element => Sync.defer(summon[NativeConverter[A]].toNative(element))))
end ResponseBody

trait LowPriorityResponseBody:
    given native: [A: NativeConverter] => ResponseBody[A]:
        private[exts] def encode(body: A)(using Frame): ResponseBody.Outgoing < Sync =
          Sync.defer(ResponseBody.Outgoing.Value(summon[NativeConverter[A]].toNative(body)))
