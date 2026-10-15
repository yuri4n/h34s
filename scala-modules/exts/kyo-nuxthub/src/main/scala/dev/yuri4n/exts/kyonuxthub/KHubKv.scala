package dev.yuri4n.exts.kyonuxthub

import dev.yuri4n.exts.nuxthub.{HubKv, NativeKvSetOptions}
import kyo.{Abort, Async, Frame, Sync, `<`}
import org.getshaka.nativeconverter.{NativeConverter, fromNative}

import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.util.{Failure, Success}

/** Native Kyo operations over application-owned NuxtHub KV, independent of ZIO. */
final class KHubKv(private val native: HubKv):
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
        val options = ttlSeconds.fold[js.UndefOr[NativeKvSetOptions]](js.undefined)(new NativeKvSetOptions(_))
        native.set(key, summon[NativeConverter[A]].toNative(value), options)
      }

    def has(key: String)(using Frame): Boolean < (Async & Abort[Throwable]) = operation(native.has(key))

    def delete(key: String)(using Frame): Unit < (Async & Abort[Throwable]) = operation(native.del(key))

    /** Keys use NuxtHub/unstorage's normalized, colon-separated representation. */
    def keys(base: Option[String] = None)(using Frame): List[String] < (Async & Abort[Throwable]) =
      operation(native.keys(base.fold[js.UndefOr[String]](js.undefined)(identity))).map(_.toList)

object KHubKv:
    def apply(native: HubKv): KHubKv = new KHubKv(native)
