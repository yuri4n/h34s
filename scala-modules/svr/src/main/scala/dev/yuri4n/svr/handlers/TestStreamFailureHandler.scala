package dev.yuri4n.svr.handlers

import dev.yuri4n.exts.zioh3.{ZHandler, defineEventHandler}
import zio.*
import zio.stream.ZStream

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.annotation.JSExportTopLevel

// Created by Claude Code on 2026-10-13. This route streams "a" and "b", pauses so h3 writes them,
// and then fails. The production smoke test in scripts/smoke-build.test.mjs requests it and checks
// that the response ends with a transport error instead of staying open, and that the server still
// answers afterwards. The pause is what makes the failure late: without it the failure can reach
// h3 before the first write, and the route answers with a 500 error body instead.
@JSExportTopLevel("default", "test-stream-failure")
val streamFailureHandler = defineEventHandler:
    ZHandler.sendStream(
      ZStream("a", "b") ++ ZStream.fromZIO(
        ZIO.fail(new Exception("the source failed after two chunks")).delay(50.millis),
      ),
    )
