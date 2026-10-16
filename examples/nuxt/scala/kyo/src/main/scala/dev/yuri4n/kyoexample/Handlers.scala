package dev.yuri4n.kyoexample

import dev.yuri4n.exts.kyoh3.KHandler
import kyo.*
import scala.scalajs.js.annotation.JSExportTopLevel

@JSExportTopLevel("default", "kyo-greeting-get")
val greeting = AppHandler.define:
    Env.use[Greetings](_.greet("Kyo"))

@JSExportTopLevel("default", "kyo-greeting-post")
val personalizedGreeting = AppHandler.define:
    for
      input <- KHandler.readBody[GreetingRequest]
      greeting <- Env.use[Greetings](_.greet(input.name))
    yield greeting
