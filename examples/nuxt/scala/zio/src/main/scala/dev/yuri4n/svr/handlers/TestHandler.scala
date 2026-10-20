package dev.yuri4n.svr.handlers

import dev.yuri4n.exts.zioh3.{EventHandler, Response, Route, ZHandler}
import zio.stream.ZStream

import scala.scalajs.js.annotation.JSExportTopLevel

// The dependency-free quickstart: a streamed body. Database routes use the example's AppHandler.
// claude 2026-10-19: changed: the stream is sent with ZHandler.sendStream (a returned ZStream no longer compiles)
// claude 2026-10-20: changed: the stream is sent with Response.sendStream (ADR-0004, Q8)
@JSExportTopLevel("default", "test")
val handler: EventHandler = ZHandler.define(_ => Response.sendStream(ZStream("a", "b", "c"))).at(Route.any("/api/test"))
