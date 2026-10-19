package dev.yuri4n.exts.h3

// claude 2026-10-19: created: Sent, the result of sending a streamed response (spike, decision 1)
/** Proof that a handler sent its response itself, for example with Kyo `Response.sendStream` or
  * ZIO `ZHandler.sendStream`. Return it as the handler's result: h34s then writes nothing more.
  *
  * Only h34s creates a `Sent`, and only for the request whose response it sent.
  */
final class Sent private[exts] (private[exts] val owner: AnyRef):
    override def toString: String = "Sent"
