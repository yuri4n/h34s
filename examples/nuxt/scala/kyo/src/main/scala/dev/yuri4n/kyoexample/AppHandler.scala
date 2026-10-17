package dev.yuri4n.kyoexample

import dev.yuri4n.exts.kyoh3.{EventHandler, HandlerEffects, HandlerOptions, KHandler, Request, ResponseBody, ResponseHead}
import kyo.*

/** Immutable, application-owned services. No resource lifetime is implied by Env.run. */
object Services:
    private val config = GreetingConfig("Hello")
    val greetings: Greetings = Greetings.live(config)

/** The application's one wiring point: it provides services around each handler function.
  * KHandler supplies the request snapshot, runs Kyo, and writes the response.
  */
object AppHandler:
    /** Every response is `Cache-Control: no-store`; bodies use the default `{ "data": ... }` envelope. */
    private val options = HandlerOptions(defaults = ResponseHead.noStore)

    def define[B: ResponseBody](
        program: Request => B < (HandlerEffects & Env[Greetings]),
    )(using Frame): EventHandler =
      KHandler.define(options)(request => Env.run(Services.greetings)(program(request)))
