package dev.yuri4n.exts.zioh3

/** Proof that a toolbox operation already sent the response.
  *
  * Created by Claude Code on 2026-10-13.
  *
  * A handler that finishes with this value tells `mkHandler` to hand h3 `undefined`, so h3 writes
  * nothing more. Only toolbox operations create it: `SH3Event.sendStream`, `respondWith`,
  * `respondWithZIO`, and `sendNoContent`, and the `ZHandler` forms `sendStream`, `respondWith`,
  * and `sendNoContent`. Handler code never names it. A handler ends with one of those calls, and
  * the proof is its result.
  *
  * This file has no ZIO or Scala.js imports, but it lives in the zio-h3 artifact. Before a Kyo
  * integration can return the same proof, the class moves to a shared module without ZIO; the
  * `private[exts]` creator is what allows that move without an API change.
  */
final class Responded private ():
    override def toString: String = "Responded"

object Responded:
    /** The single value. Visible to toolbox modules under `dev.yuri4n.exts`, not to applications. */
    private[exts] val value: Responded = new Responded()
