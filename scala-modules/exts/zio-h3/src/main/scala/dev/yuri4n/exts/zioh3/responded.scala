package dev.yuri4n.exts.zioh3

/** Proof that a toolbox operation already sent the response.
  *
  * Created by Claude Code on 2026-10-13. Changed by Claude Code on 2026-10-13: `send` joined the
  * list of operations.
  *
  * A handler that finishes with this value tells `mkHandler` to hand h3 `undefined`, so h3 writes
  * nothing more. Only toolbox operations create it: `SH3Event.send`, `sendStream`, `respondWith`,
  * `respondWithZIO`, and `sendNoContent`, and their `ZHandler` forms. Handler code never names it.
  * A handler ends with one of those calls, and the proof is its result.
  *
  * This file has no ZIO or Scala.js imports. A future Kyo integration under `dev.yuri4n.exts` can
  * hand out the same value.
  */
final class Responded private ():
    override def toString: String = "Responded"

object Responded:
    /** The single value. Visible to toolbox modules under `dev.yuri4n.exts`, not to applications. */
    private[exts] val value: Responded = new Responded()
