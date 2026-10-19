package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.kyoh3.ResponseBody
import kyo.{Frame, Sync, `<`}
import org.getshaka.nativeconverter.NativeConverter
import zio.Runtime

import scala.annotation.implicitNotFound

// claude 2026-10-19: changed: streams are sent with ZHandler.sendStream; Sent is a body; the ZStream givens and the streamed flag are gone
/** What a ZIO handler may return as its response body.
  *
  *   - any `A` with a `NativeConverter` (converted after the handler, inside the request);
  *   - `Unit` (no body: 204 unless the handler set another status; no converter needed);
  *   - `Sent`, the result of `ZHandler.sendStream(stream)`: the handler sent a stream itself.
  *
  * A `ZStream` is not a body: send it with `ZHandler.sendStream` as the handler's last step.
  */
@implicitNotFound(
  "h34s cannot send ${B} as a response body. Return a value with a NativeConverter, return Unit for a 204, or end the handler with ZHandler.sendStream(stream) to stream text",
)
trait ZResponseBody[B]:
    private[exts] def toCore(body: B, runtime: Runtime[Any])(using Frame): ResponseBody.Outgoing < Sync

object ZResponseBody extends LowPriorityZResponseBody:
    given unit: ZResponseBody[Unit]:
        private[exts] def toCore(body: Unit, runtime: Runtime[Any])(using Frame): ResponseBody.Outgoing < Sync =
          ResponseBody.Outgoing.Empty

    // claude 2026-10-19: created: the body of a handler that ends with ZHandler.sendStream
    given sent: ZResponseBody[Sent]:
        private[exts] def toCore(body: Sent, runtime: Runtime[Any])(using Frame): ResponseBody.Outgoing < Sync =
          ResponseBody.Outgoing.Sent(body)

    /** The core's body type class for a ZIO body. */
    private[exts] def core[B](body: ZResponseBody[B], runtime: Runtime[Any]): ResponseBody[B] =
      new ResponseBody[B]:
          private[exts] def encode(value: B)(using Frame): ResponseBody.Outgoing < Sync = body.toCore(value, runtime)
end ZResponseBody

trait LowPriorityZResponseBody:
    given native: [A: NativeConverter] => ZResponseBody[A]:
        private[exts] def toCore(body: A, runtime: Runtime[Any])(using Frame): ResponseBody.Outgoing < Sync =
          Sync.defer(ResponseBody.Outgoing.Value(summon[NativeConverter[A]].toNative(body)))
