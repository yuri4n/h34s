package dev.yuri4n.svr.handlers

import dev.yuri4n.exts.zioh3.{EventHandler, Route, ZHandler}
import zio.ZIO
import zio.stream.ZStream

import scala.scalajs.js.annotation.JSExportTopLevel

// The dependency-free quickstart: a streamed body. Database routes use the example's AppHandler.
@JSExportTopLevel("default", "test")
val handler: EventHandler = ZHandler.define(_ => ZIO.succeed(ZStream("a", "b", "c"))).at(Route.any("/api/test"))
