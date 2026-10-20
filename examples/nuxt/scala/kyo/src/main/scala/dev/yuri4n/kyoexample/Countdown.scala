// claude 2026-10-20: created: the server-sent events route that the streaming guide shows (ADR-0007)
package dev.yuri4n.kyoexample

import dev.yuri4n.exts.kyoh3.*
import dev.yuri4n.exts.ntcvkyoschema.NativeSchema
import kyo.*

import scala.scalajs.js.annotation.JSExportTopLevel

// #region sse-handler
/** One event of the countdown: the seconds that remain. */
final case class Countdown(remaining: Int) derives Schema, NativeSchema

/** `GET /api/kyo/countdown`: the events 3, 2, 1 and 0, one per second, as `tick` events. A chunk of
  * one element sends each event when it is ready, not the whole chunk at its end.
  */
@JSExportTopLevel("default", "kyo-countdown")
val countdown: EventHandler = KHandler
  .define: _ =>
      val ticks = Stream.init(3 to 0 by -1, chunkSize = 1).map: remaining =>
          Async.sleep(1.second).andThen(ServerEvent.json(Countdown(remaining), event = Some("tick")))
      Response.sendEvents(ticks)
  .at(Route.get("/api/kyo/countdown"))
// #endregion sse-handler
