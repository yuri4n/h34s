package dev.yuri4n.exts.zionuxthub

import dev.yuri4n.exts.h3.ConfigError
import dev.yuri4n.exts.kyonuxthub.KHubKv
import kyo.ZIOs
import org.getshaka.nativeconverter.NativeConverter
import zio.{IO, Task}

/** ZIO operations over a Nitro storage mount (NuxtHub KV and cache are mounts).
  *
  * Open one with `ZHubKv.mount(base)`. Every operation is a lazy ZIO value: nothing runs until you
  * run it, and each run calls the storage again. Storage rejections, synchronous storage throws and
  * converter exceptions are typed failures. Interrupting an operation does not cancel a storage
  * call that already started. The operations run the Kyo `KHubKv` ones.
  */
final class ZHubKv private (kv: KHubKv):
    /** Missing keys and stored null both become None, matching NuxtHub's get result. */
    def get[A: NativeConverter](key: String): Task[Option[A]] = ZIOs.run(kv.get[A](key))

    def set[A: NativeConverter](key: String, value: A, ttlSeconds: Option[Double] = None): Task[Unit] =
      ZIOs.run(kv.set(key, value, ttlSeconds))

    def has(key: String): Task[Boolean] = ZIOs.run(kv.has(key))

    def delete(key: String): Task[Unit] = ZIOs.run(kv.delete(key))

    /** Keys use NuxtHub/unstorage's normalized, colon-separated representation. */
    def keys(base: Option[String] = None): Task[List[String]] = ZIOs.run(kv.keys(base))

object ZHubKv:
    /** The Nitro storage mount `base` (Nitro `useStorage(base)`), for example `"kv"` or `"cache"`
      * with NuxtHub. `""` is the root storage. Fails with `ConfigError` when the h34s Nitro plugin is
      * not installed: the module registers it, so add `h34s` to your Nuxt modules.
      */
    def mount(base: String): IO[ConfigError, ZHubKv] = ZIOs.run(KHubKv.mount(base)).map(new ZHubKv(_))
