package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.h3.internal.H3Event

import scala.scalajs.js

// claude 2026-10-19: created: HandlerTransport (first named Exchange, which clashes with kyo.Exchange under `import kyo.*`), the per-request transport capability behind Response.sendStream (spike)
/** The native request and response behind one handler call.
  *
  * `KHandler.define` provides it to the program through Kyo `Env`, as part of `HandlerEffects`.
  * `Response.sendStream` uses it to write the head and the stream. Application code never builds
  * or reads one.
  */
final class HandlerTransport private[exts] (
    private[exts] val event: H3Event,
    private[exts] val options: HandlerOptions,
    settle: () => Unit,
):
    import HandlerTransport.State

    // claude 2026-10-19: created: who owns the response: the core (Open), a running sendStream, or the transport (Sent)
    private[exts] var state: State = State.Open

    private var finish: js.Function1[Unit, Any] = _ => ()

    // claude 2026-10-19: created: completes after the whole request (worker, scope, finalizers) has finished
    /** Completes after the request's worker and its scope have finished. A socketless stream's
      * `cancel` waits for it, so a caller that cancels waits for the callee's request cleanup too.
      */
    private[exts] val finished: js.Promise[Unit] = new js.Promise[Unit]((resolve, _) => finish = resolve)

    private[exts] def markFinished(): Unit = finish(())

    // claude 2026-10-19: created: settle the h3 handler promise before the program ends (socketless streams)
    /** Resolves the h3 handler promise now. Nitro's local fetch takes the head and the stream at
      * that moment; the program and the request scope keep running until the stream ends.
      */
    private[exts] def settleEarly(): Unit = settle()

    private[exts] def sent: Boolean = state == State.Sent
end HandlerTransport

object HandlerTransport:
    // claude 2026-10-19: created: a transport without a native response, so a handler function stays testable without h3 (C13)
    /** A transport without a native response, for calling a handler function directly in a test.
      * `Response.sendStream` fails there with an `IllegalStateException`. Use a new one per call.
      */
    def detached: HandlerTransport = new HandlerTransport(null, HandlerOptions.default, () => ())

    // claude 2026-10-19: created: response ownership states
    private[exts] enum State:
        /** The core writes the response after the program. */
        case Open

        /** `sendStream` runs; nothing reached the client yet. */
        case Streaming

        /** The transport owns the response; the core writes nothing more. */
        case Sent
end HandlerTransport
