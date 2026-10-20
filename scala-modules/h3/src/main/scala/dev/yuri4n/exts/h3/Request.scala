package dev.yuri4n.exts.h3

import dev.yuri4n.exts.h3.internal.{BodyReadFailure, BodyReader, H3, H3Event, Nitro, NitroRequestContext}

import java.nio.charset.StandardCharsets
import scala.scalajs.js

/** An immutable snapshot of the HTTP request, taken once when the handler starts.
  *
  * The body is not part of the snapshot. It is a one-shot input: read it with the runtime's
  * extensions (`request.body.json[A]`, `request.body.text()`). Reading it again returns the same
  * bytes.
  *
  * `development` and `debug` come only from the h34s Nitro plugin (Nuxt development mode and the
  * `h34s.debug` runtime config). A request can never set them.
  */
final class Request private[exts] (
    val method: String,
    val path: String,
    headerEntries: Vector[(String, String)],
    val queryAll: Map[String, List[String]],
    val routerParams: Map[String, String],
    val cookies: Map[String, String],
    val development: Boolean,
    val debug: Boolean,
    val body: RequestBody,
    private[exts] val nitro: Option[NitroRequestContext],
):
    /** Header values by lower-case name; duplicate headers are joined with ", ". */
    lazy val headers: Map[String, String] =
      headerEntries.groupMap(_._1)(_._2).view.mapValues(_.mkString(", ")).toMap

    /** A header by case-insensitive name; duplicate headers are joined with ", ". */
    def header(name: String): Option[String] = headers.get(name.toLowerCase)

    /** Every value of a header, in request order, without joining duplicates. */
    def headerValues(name: String): List[String] =
      val key = name.toLowerCase
      headerEntries.iterator.collect { case (`key`, value) => value }.toList

    /** The first value of each query parameter. */
    lazy val query: Map[String, String] = queryAll.collect { case (key, first :: _) => key -> first }

    def routerParam(name: String): Option[String] = routerParams.get(name)

    def cookie(name: String): Option[String] = cookies.get(name)

    /** The token of exactly one `Authorization: Bearer <token>` header. Duplicate `Authorization`
      * headers, other schemes, and malformed values give `None`.
      */
    def bearerToken: Option[String] =
      headerValues("authorization") match
          case List(Request.Bearer(token)) => Some(token)
          case _                           => None

    // Headers can carry credentials; never print them.
    override def toString: String = s"Request($method $path)"
end Request

object Request:
    private val Bearer = "(?i)^Bearer (\\S+)$".r

    // claude 2026-10-19: changed: added `requireJsonContentType`, default `true` like HandlerOptions (issue #41)
    /** A request for tests: call a handler function directly, without h3 or Nitro.
      *
      * `runtimeConfig` is the Nuxt runtime config as JSON text, for handlers that read it per
      * request with `RuntimeConfig.get` (Kyo) or `ZRuntimeConfig.get` (ZIO). Without it, those reads
      * fail with `ConfigError`, as they do when the h34s Nitro plugin is missing.
      *
      * `requireJsonContentType` has the default of `HandlerOptions`: JSON reads of a fake request
      * with a body but without a `content-type` header answer 415, as they do in a handler.
      */
    def fake(
        method: String = "GET",
        path: String = "/",
        headers: Map[String, String] = Map.empty,
        query: Map[String, List[String]] = Map.empty,
        routerParams: Map[String, String] = Map.empty,
        cookies: Map[String, String] = Map.empty,
        body: Option[String] = None,
        development: Boolean = false,
        debug: Boolean = false,
        bodyLimit: BodyLimit = BodyLimit.default,
        runtimeConfig: Option[String] = None,
        requireJsonContentType: Boolean = true,
    ): Request =
      val entries = headers.toVector.map((name, value) => name.toLowerCase -> value)
      val contentType = entries.collectFirst { case ("content-type", value) => value }
      new Request(
        method.toUpperCase,
        path,
        entries,
        query,
        routerParams,
        cookies,
        development,
        debug,
        RequestBody.fake(body, contentType, bodyLimit, requireJsonContentType),
        runtimeConfig.map(Nitro.fakeRequest(development, debug, _)),
      )

    // claude 2026-10-19: changed: `requireJsonContentType` defaults to `true` (issue #41)
    /** Builds the snapshot from a native h3 event. `bodyLimit` is the default limit of body reads;
      * `requireJsonContentType` makes JSON reads reject a body without a content type.
      */
    private[exts] def fromEvent(
        event: H3Event,
        bodyLimit: BodyLimit = BodyLimit.default,
        requireJsonContentType: Boolean = true,
    ): Request =
      val request = event.node.req
      val entries = request.rawHeaders.toOption match
          case Some(raw) =>
            raw.toVector.grouped(2).collect { case Vector(name, value) => name.toLowerCase -> value }.toVector
          case None      =>
            request.headers.toVector.flatMap { (name, value) =>
              if js.Array.isArray(value) then value.asInstanceOf[js.Array[String]].toVector.map(name.toLowerCase -> _)
              else if js.isUndefined(value) || value == null then Vector.empty
              else Vector(name.toLowerCase -> value.toString)
            }
      val query = H3.getQuery(event).toMap.map { (key, value) =>
        key -> (
          if js.Array.isArray(value) then value.asInstanceOf[js.Array[js.Any]].toList.map(_.toString)
          else List(String.valueOf(value))
        )
      }
      val params  = H3.getRouterParams(event, js.Dynamic.literal(decode = true)).toMap
      val cookies = H3.parseCookies(event).toMap
      val contentType = entries.collectFirst { case ("content-type", value) => value }
      new Request(
        Option(event.method).getOrElse("GET").toUpperCase,
        Option(event.path).getOrElse("/"),
        entries,
        query,
        params,
        cookies,
        Nitro.development(event),
        Nitro.debug(event),
        RequestBody.native(event, contentType, bodyLimit, requireJsonContentType),
        Nitro.request(event),
      )
    end fromEvent
end Request

/** The maximum number of body bytes h34s buffers. Larger bodies fail with 413. */
final case class BodyLimit(bytes: Long)

object BodyLimit:
    /** 1 MiB. */
    val default: BodyLimit = BodyLimit(1L << 20)

/** The request body: a one-shot input that h34s reads at most once.
  *
  * Read it through the runtime extensions. The first read fixes the outcome: later reads return
  * the same text, and a body that exceeded a limit stays rejected. Reads without an explicit
  * limit use `defaultLimit`: the handler's `HandlerOptions.bodyLimit`.
  */
final class RequestBody private (
    source: RequestBody.Source,
    private[exts] val contentType: Option[String],
    private[exts] val defaultLimit: BodyLimit,
    private[exts] val requireJsonContentType: Boolean,
):
    import RequestBody.*

    private var state: State = source match
        case Source.Fake(text) => State.Done(Right(text.map(value => Loaded(value, utf8Length(value)))))
        case Source.Native(_)  => State.Idle

    /** Reads the body as UTF-8 text. `None` means the request has no body. The callback runs once. */
    private[exts] def readText(limit: BodyLimit)(done: Either[BodyReadFailure, Option[String]] => Unit): Unit =
      state match
          case State.Done(result)     => deliver(limit, done, result)
          case State.Reading(waiters) => state = State.Reading(waiters :+ (limit -> done))
          case State.Idle             =>
            state = State.Reading(List(limit -> done))
            source match
                case Source.Native(event) =>
                  BodyReader.read(event, limit.bytes) { outcome =>
                    val result = outcome.map(_.map(bytes => Loaded(BodyReader.decodeUtf8(bytes), bytes.byteLength.toLong)))
                    val waiters = state match
                        case State.Reading(pending) => pending
                        case _                      => Nil
                    state = State.Done(result)
                    waiters.foreach((waiterLimit, callback) => deliver(waiterLimit, callback, result))
                  }
                case Source.Fake(_)       => ()

    override def toString: String = "RequestBody"
end RequestBody

object RequestBody:
    // claude 2026-10-19: changed: `requireJsonContentType` is explicit, no default (issue #41)
    private[exts] def native(
        event: H3Event,
        contentType: Option[String],
        defaultLimit: BodyLimit,
        requireJsonContentType: Boolean,
    ): RequestBody =
      new RequestBody(Source.Native(event), contentType, defaultLimit, requireJsonContentType)

    // claude 2026-10-19: changed: the fake body carries `requireJsonContentType` (issue #41)
    private[exts] def fake(
        text: Option[String],
        contentType: Option[String],
        defaultLimit: BodyLimit,
        requireJsonContentType: Boolean,
    ): RequestBody =
      new RequestBody(Source.Fake(text), contentType, defaultLimit, requireJsonContentType)

    private enum Source:
        case Native(event: H3Event)
        case Fake(text: Option[String])

    private final case class Loaded(text: String, bytes: Long)

    private type Callback = Either[BodyReadFailure, Option[String]] => Unit

    private enum State:
        case Idle
        case Reading(waiters: List[(BodyLimit, Callback)])
        case Done(result: Either[BodyReadFailure, Option[Loaded]])

    private def deliver(limit: BodyLimit, done: Callback, result: Either[BodyReadFailure, Option[Loaded]]): Unit =
      result match
          case Right(Some(loaded)) if loaded.bytes > limit.bytes => done(Left(BodyReadFailure.TooLarge))
          case other                                             => done(other.map(_.map(_.text)))

    private def utf8Length(text: String): Long = text.getBytes(StandardCharsets.UTF_8).length.toLong
end RequestBody
