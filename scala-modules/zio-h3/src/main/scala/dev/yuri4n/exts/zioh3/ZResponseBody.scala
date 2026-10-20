package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.h3.WireShape
import dev.yuri4n.exts.h3.internal.BodyShape
import dev.yuri4n.exts.kyoh3.ResponseBody
import kyo.{Frame, Sync, `<`}
import org.getshaka.nativeconverter.NativeConverter
import zio.Runtime

import scala.annotation.implicitNotFound

// claude 2026-10-19: changed: streams are sent with ZHandler.sendStream; Sent is a body; the ZStream givens and the streamed flag are gone
// claude 2026-10-20: changed: the stream operations are Response.sendStream and Response.sendEvents, in both runtimes (ADR-0004, Q8; ADR-0007)
/** What a ZIO handler may return as its response body.
  *
  *   - any `A` with a `NativeConverter` (converted after the handler, inside the request);
  *   - `Unit` (no body: 204 unless the handler set another status; no converter needed);
  *   - `Sent`, the result of `Response.sendStream(stream)` or `Response.sendEvents(events)`: the
  *     handler sent a stream itself.
  *
  * A `ZStream` is not a body: send it with `Response.sendStream` (or a stream of `ServerEvent`
  * with `Response.sendEvents`) as the handler's last step.
  */
@implicitNotFound(
  "h34s cannot send ${B} as a response body. Return a value with a NativeConverter, return Unit for a 204, or end the handler with Response.sendStream(stream) to stream text or Response.sendEvents(events) to stream server-sent events",
)
trait ZResponseBody[B]:
    private[exts] def toCore(body: B, runtime: Runtime[Any])(using Frame): ResponseBody.Outgoing < Sync

    /** What the body looks like on the wire, for the route's generated TypeScript declaration. */
    private[exts] def shape: BodyShape

object ZResponseBody extends LowPriorityZResponseBody:
    given unit: ZResponseBody[Unit]:
        private[exts] def toCore(body: Unit, runtime: Runtime[Any])(using Frame): ResponseBody.Outgoing < Sync =
          ResponseBody.Outgoing.Empty

        private[exts] def shape: BodyShape = BodyShape.Empty

    // claude 2026-10-19: created: the body of a handler that ends with ZHandler.sendStream
    given sent: ZResponseBody[Sent]:
        private[exts] def toCore(body: Sent, runtime: Runtime[Any])(using Frame): ResponseBody.Outgoing < Sync =
          ResponseBody.Outgoing.Sent(body)

        // claude 2026-10-20: changed: a sent stream is UTF-8 text, so its route is typed `string` (PR #15's registry)
        private[exts] def shape: BodyShape = BodyShape.Text

    /** The core's body type class for a ZIO body. */
    private[exts] def core[B](body: ZResponseBody[B], runtime: Runtime[Any]): ResponseBody[B] =
      new ResponseBody[B]:
          private[exts] def encode(value: B)(using Frame): ResponseBody.Outgoing < Sync = body.toCore(value, runtime)
          private[exts] def shape: BodyShape                                            = body.shape
end ZResponseBody

trait LowPriorityZResponseBody:
    /** `WireShape` finds the TypeScript type of what the converter writes; every type has one. */
    given native: [A: {NativeConverter, WireShape}] => ZResponseBody[A]:
        private[exts] def toCore(body: A, runtime: Runtime[Any])(using Frame): ResponseBody.Outgoing < Sync =
          Sync.defer(ResponseBody.Outgoing.Value(summon[NativeConverter[A]].toNative(body)))

        private[exts] def shape: BodyShape = BodyShape.Value(() => summon[WireShape[A]].of(summon[NativeConverter[A]]))
