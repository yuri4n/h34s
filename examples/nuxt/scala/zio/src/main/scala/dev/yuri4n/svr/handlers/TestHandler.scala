package dev.yuri4n.svr.handlers

import dev.yuri4n.exts.zioh3.{EventHandler, Route, ZHandler}
import zio.stream.ZStream

import scala.scalajs.js.annotation.JSExportTopLevel

// The dependency-free quickstart: a streamed body. Database routes use the example's AppHandler.
// claude 2026-10-19: changed: the stream is sent with ZHandler.sendStream (a returned ZStream no longer compiles)
@JSExportTopLevel("default", "test")
val handler: EventHandler = ZHandler.define(_ => ZHandler.sendStream(ZStream("a", "b", "c"))).at(Route.any("/api/test"))
