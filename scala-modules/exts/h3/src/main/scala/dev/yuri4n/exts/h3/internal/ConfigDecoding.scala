package dev.yuri4n.exts.h3.internal

import dev.yuri4n.exts.h3.ConfigError
import org.getshaka.nativeconverter.NativeConverter

import scala.scalajs.js
import scala.util.control.NonFatal

/** Decodes Nuxt runtime config. Error messages name only the failing path, never a value. */
private[exts] object ConfigDecoding:
    val pluginMissing: ConfigError = ConfigError("h34s Nitro plugin is not installed")

    def fromRequest[A: NativeConverter](record: Option[NitroRequestContext]): Either[ConfigError, A] =
      record.filter(context => Nitro.isFunction(context.asInstanceOf[js.Dynamic].runtimeConfig)) match
          case None          => Left(pluginMissing)
          case Some(context) => decode[A](context.runtimeConfig())

    def fromStartup[A: NativeConverter]: Either[ConfigError, A] =
      Nitro.global.filter(context => Nitro.isFunction(context.asInstanceOf[js.Dynamic].runtimeConfig)) match
          case None          => Left(pluginMissing)
          case Some(context) => decode[A](context.runtimeConfig())

    private def decode[A](read: => js.Any)(using converter: NativeConverter[A]): Either[ConfigError, A] =
      val native =
        try Right(read)
        catch case NonFatal(_) => Left(ConfigError("h34s could not read the Nuxt runtime config"))
      native.flatMap { value =>
        try Right(converter.fromNative(value))
        catch
            case NonFatal(error) =>
              Left(BodyDecoding.shapeErrorPath(error) match
                  case Some(path) => ConfigError(s"Nuxt runtime config does not match the expected shape at $path")
                  case None       => ConfigError("Nuxt runtime config could not be decoded"),
              )
      }
end ConfigDecoding
