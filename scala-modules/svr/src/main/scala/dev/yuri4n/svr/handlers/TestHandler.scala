package dev.yuri4n.svr.handlers

import dev.yuri4n.exts.zioh3.ZHandler
import zio.stream.ZStream

import scala.scalajs.js.annotation.JSExportTopLevel

// The dependency-free quickstart; database routes use the application's AppHandler.
@JSExportTopLevel("default", "test")
val handler = ZHandler.define:
    ZHandler.sendStream(ZStream("a", "b", "c"))
