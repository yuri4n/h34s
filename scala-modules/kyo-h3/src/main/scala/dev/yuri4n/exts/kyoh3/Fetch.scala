package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.h3.internal.BodyDecoding
import kyo.{Abort, Async, Frame, Scope, Stream, Sync, `<`}
import org.getshaka.nativeconverter.NativeConverter

import scala.scalajs.js
import scala.util.control.NonFatal

// claude 2026-10-19: created: Fetch, the server-side $fetch service that KHandler.define provides per request (spike, decision 2)
// claude 2026-10-20: changed: tests pass a Fetch to KHandler.test (ADR-0006, Q10)
/** The server-side `$fetch` of the current request, with Nuxt's `event.fetch` semantics.
  *
  * A path that starts with `/` is answered in-process by Nitro (no HTTP round trip), and the
  * incoming request's headers and cookies are forwarded. `KHandler.define` provides one per
  * request through Kyo `Env`; use it through `KHandler.$fetch`. In tests, pass one to
  * `KHandler.test`, for example `Fetch.fake`.
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
/** One server-side request. A path that starts with `/` stays in-process. */
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
/** A server-side response. `Plain` carries the whole body; `Streamed` carries a pull-driven body
  * that lives in the caller's `Scope`.
  */
sealed trait FetchResponse:
    def status: Int

    /** Lower-case names; repeated values joined with `", "`. */
    def headers: Map[String, String]

    def header(name: String): Option[String] = headers.get(name.toLowerCase)

    def ok: Boolean = status >= 200 && status <= 299

object FetchResponse:
    final case class Plain(status: Int, headers: Map[String, String], text: String) extends FetchResponse:
        /** Decodes the body as JSON with the `NativeConverter`. */
        def as[A: NativeConverter](using Frame): A < Abort[FetchError] = decode[A](status, text)

    /** Read `body` once. Its elements are the decoded UTF-8 text of each chunk the server sent. */
    final case class Streamed(status: Int, headers: Map[String, String], body: Stream[String, Async & Scope])
        extends FetchResponse

    // claude 2026-10-19: created: decision 3's PROPOSED detection rule, in one function so the memo can discuss it
    /** The proposed rule (not confirmed): a response is streamed when it carries the marker header
      * that `sendStream` sets, or has no `content-length`, or has a stream content type.
      *
      * Measured: Nitro's in-process local fetch never sets `content-length`, so the second clause
      * classifies every in-process response as streamed (see `NitroLocalFetchSuite`).
      */
    def isStreamed(headers: Map[String, String]): Boolean =
      headers.contains(internal.StreamResponse.marker) ||
        !headers.contains("content-length") ||
        headers.get("content-type").exists(contentType => streamTypes.exists(contentType.toLowerCase.startsWith))

    val streamTypes: List[String] = List("text/event-stream", "application/x-ndjson")

    /** Strict JSON, then the converter. Reasons never quote the body: converter messages contain
      * the values they reject.
      */
    private[exts] def decode[A](status: Int, text: String)(using converter: NativeConverter[A], frame: Frame): A < Abort[FetchError] =
      val decoded: Either[FetchError, A] =
        BodyDecoding.parseJson(Some(text)) match
            case Left(_)       => Left(FetchError.Decode(status, "the body is not valid JSON"))
            case Right(native) =>
              val input: js.Any = if js.isUndefined(native) then null else native
              try Right(converter.fromNative(input))
              catch
                  case _: VirtualMachineError => Left(FetchError.Decode(status, "the converter failed"))
                  case NonFatal(_)            => Left(FetchError.Decode(status, "the converter rejected the body"))
      decoded match
          case Right(value) => value
          case Left(error)  => Abort.fail(error)
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
end FetchError
