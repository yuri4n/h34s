package dev.yuri4n.exts.kyoh3.internal

import dev.yuri4n.exts.h3.internal.{H3Event, Nitro}

import scala.scalajs.js

/** Node `close` notifications are transport events; their callbacks stay outside the effect program. */
private[exts] final class RequestLifecycle(event: H3Event):
    private val response               = event.node.res.asInstanceOf[js.Dynamic]
    private var interrupted            = isClosed
    private var interrupt: () => Unit  = () => ()
    private var listening              = false

    private val close: js.Function0[Unit] = () =>
      interrupted = true
      interrupt()

    def disconnected: Boolean = interrupted || isClosed

    // claude 2026-10-20: created: a close that the client caused, not one that h34s caused after unlisten (ADR-0004)
    /** The client disconnected while the request listened, which interrupted the program. */
    def closedByClient: Boolean = interrupted

    private def isClosed: Boolean =
      def flag(name: String): Boolean = Nitro.isTrue(response.selectDynamic(name))
      flag("destroyed") || flag("closed") || flag("writableEnded")

    def listen(): Unit =
      if !disconnected && Nitro.isFunction(response.on) then
          listening = true
          response.on("close", close)
          if isClosed then close()

    def setInterrupt(callback: () => Unit): Unit =
      interrupt = callback
      if disconnected then callback()

    def unlisten(): Unit =
      if listening then
          listening = false
          if Nitro.isFunction(response.removeListener) then response.removeListener("close", close)
          else if Nitro.isFunction(response.off) then response.off("close", close)
      interrupt = () => ()

    /** h3 1.15's `handled` getter ignores a destroyed socket. Claim the request so h3 does not
      * manufacture a fallback 404 for a response that h34s ended or abandoned.
      */
    def claim(): js.Any =
      event.asInstanceOf[js.Dynamic].updateDynamic("_handled")(true)
      js.undefined
end RequestLifecycle
