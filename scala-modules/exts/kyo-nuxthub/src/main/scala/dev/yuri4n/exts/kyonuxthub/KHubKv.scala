package dev.yuri4n.exts.kyonuxthub

import dev.yuri4n.exts.h3.ConfigError
import dev.yuri4n.exts.h3.internal.{ConfigDecoding, Nitro, NativeStorage, NativeStorageSetOptions}
import kyo.{Abort, Async, Frame, Sync, `<`}
import org.getshaka.nativeconverter.{NativeConverter, fromNative}

import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.util.{Failure, Success}

/** Kyo operations over a Nitro storage mount (NuxtHub KV and cache are mounts).
  *
  * Open one with `KHubKv.mount(base)`. Every operation is a lazy Kyo program: nothing runs until
  * you run it, and each run calls the storage again. Storage rejections and converter exceptions
  * are typed failures (`Abort[Throwable]`).
  */
final class KHubKv private[exts] (private val native: NativeStorage):
    private def operation[A](promise: => js.Promise[A])(using Frame): A < (Async & Abort[Throwable]) =
      Abort.catching[Throwable] {
        Sync.defer {
          given ExecutionContext = ExecutionContext.global
          // Async.fromFuture treats failed Futures as panics. Carry the result
          // as a value so a storage rejection remains recoverable with Abort.
          Async.fromFuture(promise.toFuture.transform(result => Success(result))).map {
            case Success(value) => value
            case Failure(error) => Abort.fail(error)
          }
        }
      }

    /** Missing keys and stored null both become None, matching NuxtHub's get result. */
    def get[A: NativeConverter](key: String)(using Frame): Option[A] < (Async & Abort[Throwable]) =
      operation(native.get(key)).map { value =>
        Abort.catching[Throwable] {
          if value == null || js.isUndefined(value) then None else Some(value.fromNative[A])
        }
      }

    def set[A: NativeConverter](key: String, value: A, ttlSeconds: Option[Double] = None)(using Frame): Unit < (Async & Abort[Throwable]) =
      operation {
        val options = ttlSeconds.fold[js.UndefOr[NativeStorageSetOptions]](js.undefined)(new NativeStorageSetOptions(_))
        native.set(key, summon[NativeConverter[A]].toNative(value), options)
      }

    def has(key: String)(using Frame): Boolean < (Async & Abort[Throwable]) = operation(native.has(key))

    def delete(key: String)(using Frame): Unit < (Async & Abort[Throwable]) = operation(native.del(key))

    /** Keys use NuxtHub/unstorage's normalized, colon-separated representation. */
    def keys(base: Option[String] = None)(using Frame): List[String] < (Async & Abort[Throwable]) =
      operation(native.keys(base.fold[js.UndefOr[String]](js.undefined)(identity))).map(_.toList)

object KHubKv:
    /** The Nitro storage mount `base` (Nitro `useStorage(base)`), for example `"kv"` or `"cache"`
      * with NuxtHub. `""` is the root storage. Fails with `ConfigError` when the h34s Nitro plugin is
      * not installed: the module registers it, so add `h34s` to your Nuxt modules.
      */
    def mount(base: String)(using Frame): KHubKv < (Sync & Abort[ConfigError]) =
      Sync.defer(open(base)).map(result => Abort.get(result))

    private def open(base: String): Either[ConfigError, KHubKv] =
      Nitro.global.filter(record => Nitro.isFunction(record.asInstanceOf[js.Dynamic].storage)) match
          case None         => Left(ConfigDecoding.pluginMissing)
          case Some(record) =>
            val storage = record.storage(base)
            if Nitro.isObject(storage) then Right(new KHubKv(storage))
            else Left(ConfigError(s"Nitro storage mount '$base' is not available"))

    private[exts] def fromNative(native: NativeStorage): KHubKv = new KHubKv(native)
