package dev.yuri4n.exts.h3

import dev.yuri4n.exts.h3.internal.HandlerMetadata

import scala.scalajs.js

/** A native h3 event handler. Export it as the default of a Scala.js module, and give it a route,
  * so that `h34s-scala` and the h34s Nuxt module mount it in Nitro:
  *
  * {{{
  * @JSExportTopLevel("default", "greeting-get")
  * val greeting: EventHandler = KHandler.define(request => ...).at(Route.get("/api/greeting"))
  * }}}
  */
opaque type EventHandler = js.Any

object EventHandler:
    private[exts] def fromNative(handler: js.Any): EventHandler = handler

    extension (handler: EventHandler)
        private[exts] def toNative: js.Any = handler

        /** This handler, mounted at `route`.
          *
          * After linking, `h34s-scala` finds the route on the module's default export and the Nuxt
          * module registers it with Nitro, so you need no file in `server/api`. The result is a new
          * handler; the original is unchanged.
          */
        def at(route: Route): EventHandler = HandlerMetadata.mount(handler, route)

@deprecated("Use EventHandler", "0.2.0")
type CommonEvHandler = EventHandler
