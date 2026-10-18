package dev.yuri4n.exts.h3.internal

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSImport, JSName}

/** Hand-written facades for the h3 1.15 functions that h34s uses.
  *
  * Everything here is internal. Public h34s signatures never mention these types.
  */
private[exts] object H3:
    @js.native @JSImport("h3", "defineEventHandler")
    def defineEventHandler(handler: js.Function1[H3Event, js.Any]): js.Any = js.native

    @js.native @JSImport("h3", "sendStream")
    def sendStream(event: H3Event, stream: js.Any): js.Promise[Unit] = js.native

    @js.native @JSImport("h3", "setResponseStatus")
    def setResponseStatus(event: H3Event, code: Int): Unit = js.native

    @js.native @JSImport("h3", "setResponseHeader")
    def setResponseHeader(event: H3Event, name: String, value: String): Unit = js.native

    @js.native @JSImport("h3", "appendResponseHeader")
    def appendResponseHeader(event: H3Event, name: String, value: String): Unit = js.native

    @js.native @JSImport("h3", "setCookie")
    def setCookie(event: H3Event, name: String, value: String, options: js.Object): Unit = js.native

    @js.native @JSImport("h3", "deleteCookie")
    def deleteCookie(event: H3Event, name: String, options: js.Object): Unit = js.native

    /** `string | string[]` values, as ufo's `parseQuery` returns them. */
    @js.native @JSImport("h3", "getQuery")
    def getQuery(event: H3Event): js.Dictionary[js.Any] = js.native

    @js.native @JSImport("h3", "getRouterParams")
    def getRouterParams(event: H3Event, options: js.Object): js.Dictionary[String] = js.native

    @js.native @JSImport("h3", "parseCookies")
    def parseCookies(event: H3Event): js.Dictionary[String] = js.native
end H3

/** The members of h3's `H3Event` that h34s reads. */
@js.native
private[exts] trait H3Event extends js.Object:
    def method: String = js.native
    def path: String = js.native
    def context: js.Dictionary[js.Any] = js.native
    def node: NodeContext = js.native
    def handled: Boolean = js.native

@js.native
private[exts] trait NodeContext extends js.Object:
    def req: NodeRequest = js.native
    def res: NodeResponse = js.native

/** Node `IncomingMessage` members used for headers and bounded body reads. */
@js.native
private[exts] trait NodeRequest extends js.Object:
    def headers: js.Dictionary[js.Any] = js.native
    def rawHeaders: js.UndefOr[js.Array[String]] = js.native

/** Node `ServerResponse` members used by the request lifecycle and response writing. */
@js.native
private[exts] trait NodeResponse extends js.Object:
    var statusCode: Int = js.native
    def headersSent: js.UndefOr[Boolean] = js.native
    def socket: js.Any = js.native
    def destroy(): Unit = js.native

/** The per-request record installed by the h34s Nitro plugin at `event.context.h34s`. */
@js.native
private[exts] trait NitroRequestContext extends js.Object:
    def version: js.Any = js.native
    def development: js.Any = js.native
    def debug: js.Any = js.native
    def runtimeConfig(): js.Any = js.native
    def storage(base: String): NativeStorage = js.native

/** The startup record installed by the h34s Nitro plugin at `globalThis[Symbol.for("h34s.nitro")]`. */
@js.native
private[exts] trait NitroGlobalContext extends js.Object:
    def version: js.Any = js.native
    def development: js.Any = js.native
    def debug: js.Any = js.native
    def runtimeConfig(): js.Any = js.native
    def storage(base: String): NativeStorage = js.native
    def onClose(close: js.Function0[js.Any]): js.Function0[js.Any] = js.native

/** An unstorage `Storage` (Nitro `useStorage(base)`); NuxtHub KV and cache are mounts of it. */
@js.native
private[exts] trait NativeStorage extends js.Object:
    def get(key: String): js.Promise[js.Any] = js.native
    def set(key: String, value: js.Any, options: js.UndefOr[NativeStorageSetOptions] = js.undefined): js.Promise[Unit] =
      js.native
    def has(key: String): js.Promise[Boolean] = js.native
    def del(key: String): js.Promise[Unit] = js.native
    def keys(base: js.UndefOr[String] = js.undefined): js.Promise[js.Array[String]] = js.native

/** Storage TTL in seconds; support and expiry behavior depend on the driver. */
private[exts] final class NativeStorageSetOptions(val ttl: Double) extends js.Object

private[exts] object Symbols:
    val nitro: js.Symbol = js.Symbol.forKey("h34s.nitro")
    val rawBody: js.Symbol = js.Symbol.forKey("h3RawBody")
    val handler: js.Symbol = js.Symbol.forKey("h34s.handler")

@js.native
private[exts] trait GlobalWithNitro extends js.Object:
    @JSName(Symbols.nitro)
    def h34sNitro: js.UndefOr[NitroGlobalContext] = js.native

/** A native handler function with the h34s record of `HandlerMetadata`. */
@js.native
private[exts] trait WithHandlerMetadata extends js.Object:
    @JSName(Symbols.handler)
    def h34sHandler: js.UndefOr[js.Any] = js.native

/** h3 caches a raw body Promise under `Symbol.for("h3RawBody")` on the Node request. */
@js.native
private[exts] trait WithRawBody extends js.Object:
    @JSName(Symbols.rawBody)
    def h3RawBody: js.Any = js.native

/** Reads the Nitro records defensively: tests and non-Nitro hosts have none. */
private[exts] object Nitro:
    def request(event: H3Event): Option[NitroRequestContext] =
      val context = event.asInstanceOf[js.Dynamic].context
      if !isObject(context) then None
      else
        val record = context.h34s
        if isObject(record) then Some(record.asInstanceOf[NitroRequestContext]) else None

    def global: Option[NitroGlobalContext] =
      val globalThis = js.Dynamic.global.globalThis
      if !isObject(globalThis) then None
      else
        val record = globalThis.asInstanceOf[GlobalWithNitro].h34sNitro
        if isObject(record) then Some(record.asInstanceOf[NitroGlobalContext]) else None

    /** Development mode is enabled only by the boolean `true`, never by strings or numbers. */
    def development(event: H3Event): Boolean =
      request(event).exists(record => isTrue(record.asInstanceOf[js.Dynamic].development))

    /** The `h34s.debug` runtime config flag, as the plugin read it at startup. Only boolean `true`. */
    def debug(event: H3Event): Boolean =
      request(event).exists(record => isTrue(record.asInstanceOf[js.Dynamic].debug))

    /** Diagnostics (causes and stack text) may be sent only in development or debug mode. */
    def diagnostics(event: H3Event): Boolean = development(event) || debug(event)

    def isTrue(value: js.Any): Boolean =
      js.typeOf(value) == "boolean" && value.asInstanceOf[Boolean]

    def isFunction(value: js.Any): Boolean = js.typeOf(value) == "function"

    def isObject(value: js.Any): Boolean =
      value != null && js.typeOf(value) == "object"

    /** A per-request record for `Request.fake`: only the flags and a runtime config parsed from
      * JSON text (parsed on each read, like Nitro's per-request copy).
      */
    def fakeRequest(development: Boolean, debug: Boolean, runtimeConfigJson: String): NitroRequestContext =
      val readConfig: js.Function0[js.Any] = () => js.JSON.parse(runtimeConfigJson)
      js.Dynamic.literal(version = 1, development = development, debug = debug, runtimeConfig = readConfig)
        .asInstanceOf[NitroRequestContext]
end Nitro
