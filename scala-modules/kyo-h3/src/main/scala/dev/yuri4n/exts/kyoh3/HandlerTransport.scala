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
    // claude 2026-10-20: created: ends the connection after a failure without reporting a disconnect (ADR-0004)
    abandonConnection: () => Unit = () => (),
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

    // claude 2026-10-20: created: the stream failure that failed the sendStream step, for the core's log (ADR-0004, Q3)
    /** The failure of the sent stream, if it failed after the head. The core logs it once, after the
      * program, unless the program failed with its own error (which the core logs instead).
      */
    private[exts] var streamFailure: Option[Throwable] = None

    // claude 2026-10-20: created: ends a connection whose head is out, after a failure (ADR-0004, Q1)
    /** Ends the connection so that the client cannot take a partial body for a complete one. The
      * request stops listening for a disconnect first, so ending the connection does not interrupt
      * the program: the program still sees the failure and can roll back.
      */
    private[exts] def abandon(): Unit = abandonConnection()
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

        // claude 2026-10-20: changed: the head leaves at the call, so Streaming ends when the head is written (ADR-0004, Q1)
        /** `sendStream` runs and has not written the head yet; nothing reached the client. */
        case Streaming

        /** The head is out: the transport owns the response, and the core writes nothing more. */
        case Sent
end HandlerTransport
