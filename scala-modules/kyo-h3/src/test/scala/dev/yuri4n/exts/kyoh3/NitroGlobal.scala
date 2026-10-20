package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.h3.internal.Symbols

import scala.scalajs.js
import scala.scalajs.js.annotation.JSName

/** Installs or removes a fake h34s Nitro startup record, as the Nitro plugin does. */
private[exts] object NitroGlobal:
    @js.native
    private trait MutableGlobal extends js.Object:
        @JSName(Symbols.nitro)
        var h34sNitro: js.Any = js.native

    private def global: MutableGlobal = js.Dynamic.global.globalThis.asInstanceOf[MutableGlobal]

    def install(record: js.Object): Unit = global.h34sNitro = record
    def remove(): Unit                   = global.h34sNitro = js.undefined
