package dev.yuri4n.svr.handlers

import dev.yuri4n.exts.ntcvzio.given
import dev.yuri4n.exts.zioh3.{ZHandler, defineEventHandler}
import org.getshaka.nativeconverter.NativeConverter.given
import zio.stream.ZStream

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.annotation.JSExportTopLevel

// Changed by Claude Code on 2026-10-13: removed a local `NativeConverter[Unit]` that returned the
// string "undefined". After `sendStream` ends the response, the handler's result still reaches h3.
// The library converter from `ntcvzio` returns JavaScript `undefined`, which h3 ignores for a
// handled event. The string form made h3 treat it as a response body: h3 tried to set
// `content-type` after the headers were sent, Node threw `ERR_HTTP_HEADERS_SENT`, and Nitro hid
// the error because the event was already handled. The client still received `abc` either way,
// so this is a cleanup that removes a hidden error, not the transport fix.
@JSExportTopLevel("default", "test")
val handler = defineEventHandler:
    ZHandler.sendStream(ZStream("a", "b", "c"))
