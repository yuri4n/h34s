package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.h3.internal.ConfigDecoding
import kyo.{Abort, Frame, Sync, `<`}
import org.getshaka.nativeconverter.NativeConverter

/** Nuxt `runtimeConfig` (private and public keys), decoded into your own type.
  *
  * The h34s Nitro plugin supplies it. Nitro applies `NUXT_*` environment overrides. Decode
  * failures are `ConfigError`s that name only the failing path, and become an opaque 500.
  */
object RuntimeConfig:
    /** The config as Nitro resolves it for this request. */
    def get[A: NativeConverter](request: Request)(using Frame): A < (Sync & Abort[ConfigError]) =
      Sync.defer(ConfigDecoding.fromRequest[A](request.nitro)).map(result => Abort.get(result))

    /** The startup config (Nitro's shared copy), for application-scoped resources. */
    def load[A: NativeConverter](using Frame): A < (Sync & Abort[ConfigError]) =
      Sync.defer(ConfigDecoding.fromStartup[A]).map(result => Abort.get(result))
