package dev.yuri4n.exts.h3

// claude 2026-10-19: created: Sent, the result of sending a streamed response (spike, decision 1)
// claude 2026-10-20: changed: both runtimes send with Response.sendStream (ADR-0004, Q8)
/** Proof that a handler sent its response itself, with `Response.sendStream` in Kyo or in ZIO.
  * Return it as the handler's result: h34s then writes nothing more.
  *
  * Only h34s creates a `Sent`, and only for the request whose response it sent.
  */
final class Sent private[exts] (private[exts] val owner: AnyRef):
    override def toString: String = "Sent"
