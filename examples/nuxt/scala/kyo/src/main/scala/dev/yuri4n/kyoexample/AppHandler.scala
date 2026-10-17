package dev.yuri4n.kyoexample

import dev.yuri4n.exts.kyoh3.{EventHandler, HandlerEffects, HandlerOptions, KHandler, Request, ResponseBody, ResponseHead, RuntimeConfig, Shared}
import kyo.*

/** Application-scoped services, built once from the startup runtime config. */
object Services:
    /** `RuntimeConfig.load` reads Nitro's startup config (with `NUXT_*` overrides). The first request
      * builds the service; every later request reuses it.
      */
    val greetings: Shared[Greetings] = Shared(RuntimeConfig.load[KyoConfig].map(config => Greetings.live(config.greeting)))

/** The application's one wiring point: it provides services around each handler function.
  * KHandler supplies the request snapshot, runs Kyo, and writes the response.
  */
object AppHandler:
    /** Every response is `Cache-Control: no-store`; bodies use the default `{ "data": ... }` envelope. */
    private val options = HandlerOptions(defaults = ResponseHead.noStore)

    def define[B: ResponseBody](
        program: Request => B < (HandlerEffects & Env[Greetings]),
    )(using Frame): EventHandler =
      KHandler.define(options)(request => Services.greetings.get.map(greetings => Env.run(greetings)(program(request))))
