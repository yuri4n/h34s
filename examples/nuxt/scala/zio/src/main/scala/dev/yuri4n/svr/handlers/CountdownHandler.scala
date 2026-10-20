package dev.yuri4n.svr.handlers

import dev.yuri4n.exts.ntcvzioschema.NativeSchema
import dev.yuri4n.exts.zioh3.*
import zio.*
import zio.schema.{Schema, derived}
import zio.stream.ZStream

import scala.scalajs.js.annotation.JSExportTopLevel

// claude 2026-10-20: created: the server-sent events route that the streaming guide shows, the ZIO counterpart of the Kyo countdown (ADR-0007)
// #region sse-handler
/** One event of the countdown: the seconds that remain. */
final case class Countdown(remaining: Int) derives Schema, NativeSchema

/** `GET /api/zio/countdown`: the events 3, 2, 1 and 0, one per second, as `tick` events. */
@JSExportTopLevel("default", "zio-countdown")
val countdown: EventHandler = ZHandler
  .define: _ =>
      val ticks = ZStream(3, 2, 1, 0).mapZIO: remaining =>
          ZIO.sleep(1.second).as(ServerEvent.json(Countdown(remaining), event = Some("tick")))
      Response.sendEvents(ticks)
  .at(Route.get("/api/zio/countdown"))
// #endregion sse-handler
