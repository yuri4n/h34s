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

final case class GreetingConfig(prefix: String)

trait Greetings:
    def greet(name: String): Greeting < Sync

object Greetings:
    def live(config: GreetingConfig): Greetings = new Greetings:
        def greet(name: String): Greeting < Sync =
          Sync.defer(Greeting(s"${config.prefix}, $name!"))
