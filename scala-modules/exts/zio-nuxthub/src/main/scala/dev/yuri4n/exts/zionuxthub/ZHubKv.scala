package dev.yuri4n.exts.zionuxthub

import dev.yuri4n.exts.nuxthub.{HubKv, NativeKvSetOptions}
import org.getshaka.nativeconverter.{NativeConverter, fromNative}
import zio.{Task, ZIO}

import scala.scalajs.js

/** Lazy ZIO operations over application-owned NuxtHub KV. No schema or runtime bridge. */
final class ZHubKv(private val native: HubKv):
    // Promise creation can throw before the asynchronous rejection channel exists.
    private def operation[A](promise: => js.Promise[A]): Task[A] =
      ZIO.attempt(promise).flatMap(value => ZIO.fromPromiseJS(value))

    /** Missing keys and stored null both become None, matching NuxtHub's get result. */
    def get[A: NativeConverter](key: String): Task[Option[A]] =
      operation(native.get(key)).flatMap { value =>
        ZIO.attempt(if value == null || js.isUndefined(value) then None else Some(value.fromNative[A]))
      }

    def set[A: NativeConverter](key: String, value: A, ttlSeconds: Option[Double] = None): Task[Unit] =
      operation {
        val options = ttlSeconds.fold[js.UndefOr[NativeKvSetOptions]](js.undefined)(new NativeKvSetOptions(_))
        native.set(key, summon[NativeConverter[A]].toNative(value), options)
      }

    def has(key: String): Task[Boolean] = operation(native.has(key))

    def delete(key: String): Task[Unit] = operation(native.del(key))

    /** Keys use NuxtHub/unstorage's normalized, colon-separated representation. */
    def keys(base: Option[String] = None): Task[List[String]] =
      operation(native.keys(base.fold[js.UndefOr[String]](js.undefined)(identity))).map(_.toList)

object ZHubKv:
    def apply(native: HubKv): ZHubKv = new ZHubKv(native)
