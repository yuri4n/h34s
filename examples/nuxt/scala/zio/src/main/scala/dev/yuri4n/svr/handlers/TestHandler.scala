package dev.yuri4n.svr.handlers

import dev.yuri4n.exts.zioh3.{EventHandler, HandlerEnv, Request, Response, Route, Sent, ZHandler}
import zio.ZIO
import zio.stream.ZStream

import scala.scalajs.js.annotation.JSExportTopLevel

// claude 2026-10-20: created: the quickstart's handler function, testable with ZHandler.test (ADR-0006, Q10)
/** The dependency-free quickstart: a streamed body. Database routes use the example's AppHandler. */
object TestHandler:
    val letters: Request => ZIO[HandlerEnv, Throwable, Sent] = _ => Response.sendStream(ZStream("a", "b", "c"))

// claude 2026-10-19: changed: the stream is sent with ZHandler.sendStream (a returned ZStream no longer compiles)
// claude 2026-10-20: changed: the stream is sent with Response.sendStream (ADR-0004, Q8), from TestHandler.letters
@JSExportTopLevel("default", "test")
val handler: EventHandler = ZHandler.define(TestHandler.letters).at(Route.any("/api/test"))
