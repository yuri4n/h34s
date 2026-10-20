// claude 2026-10-20: created: the region that the project tour shows (issue #52)
// #region tour
package dev.yuri4n.kyoexample

import dev.yuri4n.exts.ntcvkyoschema.NativeSchema
import kyo.*

/** The request model. Its one schema carries the validation; `NativeSchema` derives from it, so
  * `request.body.json[GreetingRequest]` answers 422 with these messages and the `name` path.
  */
final case class GreetingRequest(name: String) derives NativeSchema

object GreetingRequest:
    given Schema[GreetingRequest] = Schema[GreetingRequest]
      .check(_.name)(_.trim.nonEmpty, "must not be blank")
      .checkMaxLength(_.name)(80)

final case class Greeting(message: String) derives Schema, NativeSchema

/** `runtimeConfig.greeting` in `nuxt.config.ts`; Nitro overrides it with `NUXT_GREETING_SALUTATION`. */
final case class GreetingConfig(salutation: String) derives Schema, NativeSchema

/** The part of the Nuxt runtime config that the Kyo example reads. Other keys are ignored. */
final case class KyoConfig(greeting: GreetingConfig) derives Schema, NativeSchema

trait Greetings:
    def greet(name: String): Greeting < Sync

object Greetings:
    def live(config: GreetingConfig): Greetings = new Greetings:
        def greet(name: String): Greeting < Sync =
          Sync.defer(Greeting(s"${config.salutation}, $name!"))
// #endregion tour
