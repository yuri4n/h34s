package dev.yuri4n.svr.app

import dev.yuri4n.exts.ntcvzio.given
import dev.yuri4n.exts.zioh3.{ZHandler, defineEventHandler}

import scala.concurrent.ExecutionContext.Implicits.global

object AppHandler:
    // Changed by Claude Code on 2026-10-13: the handler ends with `sendNoContent`, so its result is
    // the proof that a response was sent. Ending with `()` does not compile any more. This handler
    // used to reach h3 with no response; Nitro's preemptive router answered an empty 204 and a bare
    // h3 app answered 404, neither on purpose. The `ntcvzio` import stays for `$fetch[Unit]`, which
    // decodes the fetched value, not the handler's result.
    def defineAppEventHandler() = defineEventHandler:
        ZHandler.`$fetch`[Unit]("hi") *> ZHandler.sendNoContent
