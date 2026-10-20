// claude 2026-10-20: created: the ZIO counterpart of the Kyo example's greeting models, and the region that the project tour shows (issue #52)
// #region tour
package dev.yuri4n.svr.greetings

import dev.yuri4n.exts.ntcvzioschema.NativeSchema
import dev.yuri4n.exts.zioh3.{ConfigError, ZRuntimeConfig}
import zio.schema.validation.Validation
import zio.schema.{DeriveSchema, Schema, StandardType, derived}
import zio.{UIO, ZIO, ZLayer}

/** The JSON that `POST /api/zio/greeting` reads. Its one schema carries the validation, so
  * `request.body.json[GreetingRequest]` answers 422 with the `name` path.
  */
final case class GreetingRequest(name: String) derives NativeSchema

object GreetingRequest:
    /** Trims the name, then requires 1-80 characters. The native converter uses this schema. */
    given Schema[GreetingRequest] =
      given Schema[String] = Schema.Primitive(StandardType.StringType)
        .transform(_.trim, identity)
        .validation(Validation.minLength(1) && Validation.maxLength(80))
      DeriveSchema.gen[GreetingRequest]

/** The JSON that the greeting route sends: the same shape as the Kyo route's body. */
final case class Greeting(message: String) derives Schema, NativeSchema

/** `runtimeConfig.greeting` in `nuxt.config.ts`; Nitro overrides it with `NUXT_GREETING_SALUTATION`. */
final case class GreetingConfig(salutation: String) derives Schema, NativeSchema

/** The part of the Nuxt runtime config that the greeting reads. Other keys are ignored. */
final case class GreetingsConfig(greeting: GreetingConfig) derives Schema, NativeSchema

trait Greetings:
    def greet(name: String): UIO[Greeting]

object Greetings:
    def live(config: GreetingConfig): Greetings = new Greetings:
        def greet(name: String): UIO[Greeting] = ZIO.succeed(Greeting(s"${config.salutation}, $name!"))

    /** Reads the startup runtime config. It needs no database. */
    val layer: ZLayer[Any, ConfigError, Greetings] =
      ZLayer.fromZIO(ZRuntimeConfig.load[GreetingsConfig].map(config => live(config.greeting)))
// #endregion tour
