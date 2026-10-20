package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.h3.internal.ConfigDecoding
import org.getshaka.nativeconverter.NativeConverter
import zio.{IO, ZIO}

/** Nuxt `runtimeConfig` (private and public keys), decoded into your own type.
  *
  * The h34s Nitro plugin supplies it. Nitro applies `NUXT_*` environment overrides. Decode
  * failures are `ConfigError`s that name only the failing path; an unhandled one becomes an
  * opaque 500.
  */
object ZRuntimeConfig:
    /** The config as Nitro resolves it for this request. */
    def get[A: NativeConverter](request: Request): IO[ConfigError, A] =
      ZIO.suspendSucceed(ZIO.fromEither(ConfigDecoding.fromRequest[A](request.nitro)))

    /** The startup config (Nitro's shared copy), for application-scoped resources such as `ZShared`. */
    def load[A: NativeConverter]: IO[ConfigError, A] =
      ZIO.suspendSucceed(ZIO.fromEither(ConfigDecoding.fromStartup[A]))
