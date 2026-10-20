package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.h3.internal.{BodyDecoding, H3}
import kyo.{Abort, Async, Frame, Scope, Stream, Sync, `<`}
import org.getshaka.nativeconverter.NativeConverter

import scala.scalajs.js
import scala.util.control.NonFatal

// claude 2026-10-19: created: Fetch, the server-side $fetch service that KHandler.define provides per request (spike, decision 2)
// claude 2026-10-20: changed: tests pass a Fetch to KHandler.test (ADR-0006, Q10); incoming headers reach only in-process paths (ADR-0005, Q5)
/** The server-side `$fetch` of the current request.
  *
  * A path that starts with `/` is answered in-process by Nitro (no HTTP round trip), and the
  * incoming request's headers and cookies are forwarded, as Nuxt does. An absolute URL gets only
  * the headers you pass, and the content type and length of the body you send: the user's cookies
  * and `authorization` never reach another host. This differs from Nuxt's `event.$fetch`, which
  * forwards them to any host.
  *
  * `KHandler.define` provides one per request through Kyo `Env`; use it through `KHandler.$fetch`.
  * In tests, pass one to `KHandler.test`, for example `Fetch.fake`.
  */
trait Fetch:
    /** Sends the request and returns as soon as the response head arrives. A streamed body stays
      * unread until you pull it; the current `Scope` cancels it if you do not read it to the end.
      */
    def raw(request: FetchRequest)(using Frame): FetchResponse < (Async & Abort[FetchError] & Scope)

object Fetch:
    // claude 2026-10-19: created: a Fetch for handler tests that do not call $fetch
    // claude 2026-10-20: changed: the message names KHandler.test and Fetch.fake (ADR-0006, Q10)
    /** Fails every call with an `IllegalStateException`. For handler tests that do not call `$fetch`;
      * a test that does provides its own `Fetch`.
      */
    val unavailable: Fetch = new Fetch:
        def raw(request: FetchRequest)(using Frame): FetchResponse < (Async & Abort[FetchError] & Scope) =
          Abort.panic(IllegalStateException("No server-side $fetch here: pass a Fetch to KHandler.test, such as Fetch.fake"))

    // claude 2026-10-20: created: a recording fake Fetch for KHandler.test (ADR-0006, Q10)
    /** A fake `$fetch` for `KHandler.test`. It answers each request from `responses` by its URL, as
      * the handler passes it to `$fetch` (for example `"/api/notes"`), and records every request.
      * A URL without an entry answers 404 with an empty body, like a Nitro route that does not exist.
      *
      * {{{
      * val fetch = Fetch.fake(Map("/api/greeting" -> FetchResponse.Plain(200, Map.empty, "{\"data\":\"hi\"}")))
      * KHandler.test(handler)(request, fetch).map(response => fetch.requests.map(_.url))
      * }}}
      */
    def fake(responses: Map[String, FetchResponse] = Map.empty): Recording = new Recording(responses)

    // claude 2026-10-20: created: the fake's recorded requests (ADR-0006, Q10)
    /** A fake `$fetch` that answers from a map and records the requests it got. */
    final class Recording private[Fetch] (responses: Map[String, FetchResponse]) extends Fetch:
        private var received = Vector.empty[FetchRequest]

        /** The requests that the handler sent, in order. */
        def requests: List[FetchRequest] = received.toList

        def raw(request: FetchRequest)(using Frame): FetchResponse < (Async & Abort[FetchError] & Scope) =
          Sync.defer {
            received = received :+ request
            responses.getOrElse(request.url, FetchResponse.Plain(404, Map.empty, ""))
          }
    end Recording

// claude 2026-10-19: created: the request a $fetch call sends
// claude 2026-10-20: changed: the forwarding rule (ADR-0005, Q5)
/** One server-side request. A path that starts with `/` stays in-process and also carries the
  * incoming request's headers; an absolute URL carries only `headers` and the body's own headers.
  */
final case class FetchRequest(
    url: String,
    method: String = "GET",
    headers: Seq[(String, String)] = Nil,
    body: Option[FetchBody] = None,
)

// claude 2026-10-19: created: a request body as wire text with its content type
/** A request body, already encoded for the wire. */
final case class FetchBody private (text: String, contentType: String)

object FetchBody:
    /** The value as JSON text, through its `NativeConverter`. */
    def json[B](value: B)(using converter: NativeConverter[B]): FetchBody =
      FetchBody(js.JSON.stringify(converter.toNative(value)), "application/json")

    def text(value: String): FetchBody = FetchBody(value, "text/plain; charset=utf-8")

// claude 2026-10-19: created: the response ADT; it knows whether the body is streamed (decision 3)
// claude 2026-10-20: changed: names the detection rule (ADR-0005, Q2)
/** A server-side response. `Plain` carries the whole body; `Streamed` carries a pull-driven body
  * that lives in the caller's `Scope`. `isStreamed` decides which one you get.
  */
sealed trait FetchResponse:
    def status: Int

    // claude 2026-10-20: changed: set-cookie values are joined too (ADR-0005, Q14)
    /** Lower-case names; repeated values joined with `", "`, `set-cookie` included. */
    def headers: Map[String, String]

    /** A header by case-insensitive name; repeated values joined with `", "`. */
    def header(name: String): Option[String] = headers.get(name.toLowerCase)

    // claude 2026-10-20: created: every value of a header, set-cookie apart (ADR-0005, Q14)
    /** Every value of a header by case-insensitive name, like `Request.headerValues`. Each
      * `set-cookie` value is its own entry. The Fetch API combines every other repeated header
      * into one value before h34s sees it, so such a header gives one entry with the joined text.
      */
    def headerValues(name: String): List[String] = FetchResponse.valuesOf(headers, name)

    def ok: Boolean = status >= 200 && status <= 299

object FetchResponse:
    final case class Plain(status: Int, headers: Map[String, String], text: String) extends FetchResponse:
        /** Decodes the body as JSON with the `NativeConverter`. */
        def as[A: NativeConverter](using Frame): A < Abort[FetchError] = decode[A](status, text)

    // claude 2026-10-20: changed: the body fails with a typed FetchError (ADR-0005, Q14)
    /** Read `body` once. Its elements are the decoded UTF-8 text of each chunk the server sent.
      * When the body breaks before its end (the callee's stream failed after its head, or the
      * connection closed), the read fails with `FetchError.BodyFailed`; handle it with
      * `Abort.run[FetchError]` or `Abort.recover`.
      */
    final case class Streamed(status: Int, headers: Map[String, String], body: Stream[String, Async & Scope & Abort[FetchError]])
        extends FetchResponse

    // claude 2026-10-20: changed: the marker or a stream content type, and nothing else (ADR-0005, Q2 option B)
    /** A response is `Streamed` when it carries the `x-h34s-stream` marker that `Response.sendStream`
      * sets, or a stream content type (`streamTypes`). Every other response is `Plain`, in-process
      * and over the network alike. A missing `content-length` does not count: no in-process
      * response has one.
      *
      * One known gap: a JavaScript route that streams without a stream content type is `Plain`, so
      * `raw` reads it to its end, and never returns for an endless stream.
      */
    def isStreamed(headers: Map[String, String]): Boolean =
      headers.contains(internal.StreamResponse.marker) ||
        headers.get("content-type").exists(contentType => streamTypes.exists(contentType.toLowerCase.startsWith))

    val streamTypes: List[String] = List("text/event-stream", "application/x-ndjson")

    // claude 2026-10-20: created: headerValues over the joined headers, shared with the ZIO adapter (ADR-0005, Q14)
    /** The values of one header. `set-cookie` is split as Nitro splits it (h3's
      * `splitCookiesString`, which keeps the comma of an `Expires` date); every other header is one
      * value.
      */
    private[exts] def valuesOf(headers: Map[String, String], name: String): List[String] =
      val key = name.toLowerCase
      headers.get(key) match
          case None                              => Nil
          case Some(value) if key == "set-cookie" => H3.splitCookiesString(value).toList
          case Some(value)                       => List(value)

    private[exts] def decode[A](status: Int, text: String)(using converter: NativeConverter[A], frame: Frame): A < Abort[FetchError] =
      decoded[A](status, text) match
          case Right(value) => value
          case Left(error)  => Abort.fail(error)

    // claude 2026-10-20: created: the pure decode, shared with the ZIO adapter's FetchResponse.Plain.as (moved out of decode)
    /** Strict JSON, then the converter. Reasons never quote the body: converter messages contain
      * the values they reject.
      */
    private[exts] def decoded[A](status: Int, text: String)(using converter: NativeConverter[A]): Either[FetchError, A] =
      BodyDecoding.parseJson(Some(text)) match
          case Left(_)       => Left(FetchError.Decode(status, "the body is not valid JSON"))
          case Right(native) =>
            val input: js.Any = if js.isUndefined(native) then null else native
            try Right(converter.fromNative(input))
            catch
                case _: VirtualMachineError => Left(FetchError.Decode(status, "the converter failed"))
                case NonFatal(_)            => Left(FetchError.Decode(status, "the converter rejected the body"))
end FetchResponse

// claude 2026-10-19: created: typed $fetch failures; not SafeErrors, so an unhandled one is an opaque 500
/** A failed server-side `$fetch`. None of these is a `SafeError`: if a handler does not handle it,
  * the client gets an opaque 500, never the callee's status or body.
  */
sealed abstract class FetchError(message: String) extends Exception(message)

object FetchError:
    /** `text`, `as`, `get` and `post` expect a 2xx status. */
    final case class Status(status: Int, body: String) extends FetchError(s"Server-side $$fetch got status $status")

    /** The body is not JSON, or the converter rejected it. */
    final case class Decode(status: Int, reason: String)
        extends FetchError(s"Server-side $$fetch could not decode a $status response: $reason")

    /** The request did not produce a response (for example a network error for an absolute URL). */
    final case class Failed(reason: String) extends FetchError(s"Server-side $$fetch failed: $reason")

    // claude 2026-10-20: created: a body that breaks after the head (ADR-0005, Q14)
    /** The head arrived with `status`, but the body broke before its end: the callee's stream
      * failed after its head, or the connection closed. `reason` is h34s' own text and never quotes
      * the callee's error; the server log has the cause.
      */
    final case class BodyFailed(status: Int, reason: String)
        extends FetchError(s"Server-side $$fetch lost the body of a $status response: $reason")
end FetchError
