package dev.yuri4n.exts.h3

import scala.scalajs.js

/** A native h3 event handler. Export it as the default of a Scala.js module so Nitro can mount it:
  *
  * {{{
  * @JSExportTopLevel("default", "greeting-get")
  * val greeting: EventHandler = KHandler.define(request => ...)
  * }}}
  */
opaque type EventHandler = js.Any

object EventHandler:
    private[exts] def fromNative(handler: js.Any): EventHandler = handler

    extension (handler: EventHandler) private[exts] def toNative: js.Any = handler

@deprecated("Use EventHandler", "0.2.0")
type CommonEvHandler = EventHandler
