package dev.yuri4n.exts.kyonuxthub

import dev.yuri4n.exts.h3.{ConfigError, InvalidInput, SafeError}
import dev.yuri4n.exts.h3.internal.{BodyDecoding, ConfigDecoding, Nitro, NativeStorage, NativeStorageSetOptions}
import kyo.{Abort, Async, Frame, Sync, `<`}
import org.getshaka.nativeconverter.NativeConverter

import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.util.control.{NoStackTrace, NonFatal}
import scala.util.{Failure, Success}

/** A stored value that does not match the converter you read it with.
  *
  * It is server data, not client input: unhandled, it is an opaque 500. Its message names only the
  * failing path (`root.owner.name`, or a schema path), never the key or the stored value, because
  * both can hold private data such as session ids or tokens.
  */
final case class StoredValueError(path: Option[String])
    extends RuntimeException("Stored KV value could not be decoded" + path.fold("")(at => s" at $at"))
    with NoStackTrace

/** Kyo operations over a Nitro storage mount (NuxtHub KV and cache are mounts).
  *
  * Open one with `KHubKv.mount(base)`. Every operation is a lazy Kyo program: nothing runs until
  * you run it, and each run calls the storage again. Storage rejections and converter exceptions
  * are typed failures (`Abort[Throwable]`). A stored value that does not decode is a
  * `StoredValueError`; a converter never produces an approved `SafeError` here.
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
        if value == null || js.isUndefined(value) then None
        else Sync.defer(KHubKv.decode[A](value)).map(decoded => Abort.get(decoded).map(Some(_)))
      }

    def set[A: NativeConverter](key: String, value: A, ttlSeconds: Option[Double] = None)(using Frame): Unit < (Async & Abort[Throwable]) =
      operation {
        val options = ttlSeconds.fold[js.UndefOr[NativeStorageSetOptions]](js.undefined)(new NativeStorageSetOptions(_))
        native.set(key, KHubKv.encode(value), options)
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

    /** Every converter failure becomes a `StoredValueError` without the original exception: the
      * messages of native-converter (and of many custom converters) quote the stored value.
      */
    private def decode[A](value: js.Any)(using converter: NativeConverter[A]): Either[StoredValueError, A] =
      try Right(converter.fromNative(value))
      catch
          case invalid: InvalidInput      => Left(StoredValueError(invalid.entries.flatMap(_.path).headOption))
          case NonFatal(error)            => Left(StoredValueError(BodyDecoding.shapeErrorPath(error)))
          case _: VirtualMachineError     => Left(StoredValueError(None))

    /** A `SafeError` from an encoder is not an approved client error. */
    private def encode[A](value: A)(using converter: NativeConverter[A]): js.Any =
      try converter.toNative(value)
      catch case safe: SafeError => throw IllegalStateException("A converter threw a SafeError while encoding a KV value", safe)
