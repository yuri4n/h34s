package dev.yuri4n.svr.app

import dev.yuri4n.exts.ntcvzio.given
import dev.yuri4n.exts.zioh3.{ZHandler, defineEventHandler}

import scala.concurrent.ExecutionContext.Implicits.global

object AppHandler:
    def defineAppEventHandler() = defineEventHandler:
        for _ <- ZHandler.`$fetch`[Unit]("hi")
        yield ()
