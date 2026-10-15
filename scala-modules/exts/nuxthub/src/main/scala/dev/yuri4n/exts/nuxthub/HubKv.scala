package dev.yuri4n.exts.nuxthub

import scala.scalajs.js

/** The generated @nuxthub/kv export in NuxtHub 0.10.8. Supply it from the application. */
@js.native
trait HubKv extends js.Object:
    def get(key: String): js.Promise[js.Any] = js.native
    def set(key: String, value: js.Any, options: js.UndefOr[NativeKvSetOptions] = js.undefined): js.Promise[Unit] = js.native
    def has(key: String): js.Promise[Boolean] = js.native
    def del(key: String): js.Promise[Unit] = js.native
    def keys(base: js.UndefOr[String] = js.undefined): js.Promise[js.Array[String]] = js.native

/** NuxtHub forwards TTL in seconds; support and expiry behavior depend on the driver. */
final class NativeKvSetOptions(val ttl: Double) extends js.Object
