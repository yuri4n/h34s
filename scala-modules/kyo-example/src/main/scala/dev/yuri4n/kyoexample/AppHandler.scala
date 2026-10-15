package dev.yuri4n.kyoexample

import dev.yuri4n.exts.h3.CommonEvHandler
import dev.yuri4n.exts.kyoh3.{HandlerEffects, KHandler}
import kyo.*
import org.getshaka.nativeconverter.NativeConverter

/** Immutable, application-owned services. No resource lifetime is implied by Env.run. */
object Services:
    private val config = GreetingConfig("Hello")
    val greetings: Greetings = Greetings.live(config)

/** Resolve application effects here; KHandler supplies the HTTP context and runtime. */
object AppHandler:
    def define[A: NativeConverter](
        program: => A < (HandlerEffects & Env[Greetings]),
    )(using Frame): CommonEvHandler =
      KHandler.define(Env.run(Services.greetings)(program))

    def define(
        program: => Unit < (HandlerEffects & Env[Greetings]),
    )(using Frame): CommonEvHandler =
      KHandler.define(Env.run(Services.greetings)(program))
