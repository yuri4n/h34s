package dev.yuri4n.svr.handlers

import dev.yuri4n.exts.zioh3.{ZHandler, defineEventHandler}
import zio.stream.ZStream

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.annotation.JSExportTopLevel

// Changed by Claude Code on 2026-10-13: this handler imports no converter. It ends with
// `sendStream`, whose result is the `Responded` proof; `defineEventHandler` converts that proof to
// JavaScript `undefined`, and h3 writes nothing more. An earlier version ended with `()` and kept a
// local `NativeConverter[Unit]` that returned the string "undefined". h3 treated that string as a
// second response body: it tried to set `content-type` after the headers were sent, Node threw
// `ERR_HTTP_HEADERS_SENT`, and Nitro hid the error because the event was already handled. The
// client received `abc` either way. `HandlerResultSpec` in zio-h3 now pins the rule.
@JSExportTopLevel("default", "test")
val handler = defineEventHandler:
    ZHandler.sendStream(ZStream("a", "b", "c"))
