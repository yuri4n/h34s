// claude 2026-10-20: created: the ZIO counterpart of the Kyo `personalizedGreeting`, line for line, and the regions that the site shows (issue #52)
// #region tour
package dev.yuri4n.svr.handlers

import dev.yuri4n.exts.zioh3.*
import dev.yuri4n.svr.app.AppHandler
import dev.yuri4n.svr.greetings.{GreetingRequest, Greetings}
import zio.ZIO

import scala.scalajs.js.annotation.JSExportTopLevel

// #region greeting-handler
@JSExportTopLevel("default", "zio-greeting-post")
val personalizedGreeting: EventHandler = AppHandler
  .define: request =>
      for
        input    <- request.body.json[GreetingRequest]
        greeting <- ZIO.serviceWithZIO[Greetings](_.greet(input.name))
      yield greeting
  .at(Route.post("/api/zio/greeting"))
// #endregion greeting-handler
// #endregion tour
