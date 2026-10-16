package dev.yuri4n.kyoexample

import kyo.*
import org.getshaka.nativeconverter.NativeConverter

final case class GreetingRequest(name: String) derives NativeConverter
final case class Greeting(message: String) derives NativeConverter

final case class GreetingConfig(prefix: String)

trait Greetings:
    def greet(name: String): Greeting < Sync

object Greetings:
    def live(config: GreetingConfig): Greetings = new Greetings:
        def greet(name: String): Greeting < Sync =
          Sync.defer(Greeting(s"${config.prefix}, $name!"))
