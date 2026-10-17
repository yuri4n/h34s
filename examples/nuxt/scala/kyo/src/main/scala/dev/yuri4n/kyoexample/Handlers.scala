package dev.yuri4n.kyoexample

import dev.yuri4n.exts.kyoh3.*
import kyo.*

import scala.scalajs.js.annotation.JSExportTopLevel

@JSExportTopLevel("default", "kyo-greeting-get")
val greeting: EventHandler = AppHandler.define: _ =>
    Env.use[Greetings](_.greet("Kyo"))

@JSExportTopLevel("default", "kyo-greeting-post")
val personalizedGreeting: EventHandler = AppHandler.define: request =>
    for
      input    <- request.body.json[GreetingRequest]
      greeting <- Env.use[Greetings](_.greet(input.name))
    yield greeting
