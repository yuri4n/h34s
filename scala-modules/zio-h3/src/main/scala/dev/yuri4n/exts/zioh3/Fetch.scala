package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.kyoh3
import dev.yuri4n.exts.zioh3.internal.KyoFetch
import org.getshaka.nativeconverter.NativeConverter
import zio.stream.ZStream
import zio.{IO, Scope, ZIO}

// claude 2026-10-20: created: the ZIO server-side $fetch service in HandlerEnv (ADR-0005, gap 6; ADR-0006)
/** The server-side `$fetch` of the current request, as a ZIO service.
  *
  * `ZHandler.define` provides one per request in `HandlerEnv`; use it through `ZHandler.$fetch`. In
  * tests, pass one to `ZHandler.test`, for example `Fetch.fake`. It follows the rules of the Kyo
  * core, which it runs at the boundary: a path that starts with `/` is answered in-process by Nitro,
  * with the app's `app.baseURL` in front and the incoming headers and cookies forwarded; an absolute
  * URL gets only the headers you pass.
  */
trait Fetch:
    /** Sends the request and returns as soon as the response head arrives. A streamed body stays
      * unread until you pull it; when the current `Scope` closes, h34s cancels a body that you did
      * not read to its end, and waits until the callee has finished its cleanup.
      */
    def raw(request: FetchRequest): ZIO[Scope, FetchError, FetchResponse]

// claude 2026-10-20: created: Fetch.unavailable, Fetch.fake and the bridge from the Kyo core's Fetch (ADR-0005 gap 6, ADR-0006 Q10)
object Fetch:
    /** Dies with an `IllegalStateException`. For handler tests that do not call `$fetch`; a test that
      * does provides its own `Fetch`.
      */
    val unavailable: Fetch = new Fetch:
        def raw(request: FetchRequest): ZIO[Scope, FetchError, FetchResponse] =
          ZIO.die(IllegalStateException("No server-side $fetch here: pass a Fetch to ZHandler.test, such as Fetch.fake"))

    /** A fake `$fetch` for `ZHandler.test`. It answers each request from `responses` by its URL, as
      * the handler passes it to `$fetch` (for example `"/api/notes"`), and records every request.
      * A URL without an entry answers 404 with an empty body, like a Nitro route that does not exist.
      *
      * {{{
      * val fetch = Fetch.fake(Map("/api/greeting" -> FetchResponse.Plain(200, Map.empty, "{\"data\":\"hi\"}")))
      * ZHandler.test(handler)(request, fetch).map(response => fetch.requests.map(_.url))
      * }}}
      */
    def fake(responses: Map[String, FetchResponse] = Map.empty): Recording = new Recording(responses)

    /** A fake `$fetch` that answers from a map and records the requests it got. */
    final class Recording private[Fetch] (responses: Map[String, FetchResponse]) extends Fetch:
        private var received = Vector.empty[FetchRequest]

        /** The requests that the handler sent, in order. */
        def requests: List[FetchRequest] = received.toList

        def raw(request: FetchRequest): ZIO[Scope, FetchError, FetchResponse] =
          ZIO.succeed {
            received = received :+ request
            responses.getOrElse(request.url, FetchResponse.Plain(404, Map.empty, ""))
          }
    end Recording

    /** The Kyo core's `$fetch` of a request, run at the boundary. */
    private[exts] def fromKyo(fetch: kyoh3.Fetch): Fetch = KyoFetch(fetch)
end Fetch

// claude 2026-10-20: created: the ZIO response ADT; it knows whether the body is streamed (ADR-0005, decision 3 and Q14)
/** A server-side response. `Plain` carries the whole body; `Streamed` carries a pull-driven body
  * that lives in the caller's `Scope`. A response is `Streamed` when it carries the `x-h34s-stream`
  * marker that `Response.sendStream` sets, or a stream content type (`text/event-stream`,
  * `application/x-ndjson`), and `Plain` otherwise.
  */
sealed trait FetchResponse:
    def status: Int

    /** Lower-case names; repeated values joined with `", "`, `set-cookie` included. */
    def headers: Map[String, String]

    /** A header by case-insensitive name; repeated values joined with `", "`. */
    def header(name: String): Option[String] = headers.get(name.toLowerCase)

    /** Every value of a header by case-insensitive name, like `Request.headerValues`. Each
      * `set-cookie` value is its own entry. The Fetch API combines every other repeated header
      * into one value before h34s sees it, so such a header gives one entry with the joined text.
      */
    def headerValues(name: String): List[String] = kyoh3.FetchResponse.valuesOf(headers, name)

    def ok: Boolean = status >= 200 && status <= 299

// claude 2026-10-20: created: Plain with as[A], and Streamed with a ZStream body that fails with a typed FetchError (ADR-0005 gap 6, Q14)
object FetchResponse:
    final case class Plain(status: Int, headers: Map[String, String], text: String) extends FetchResponse:
        /** Decodes the body as JSON with the `NativeConverter`, as it arrives (an h34s route's
          * `{"data": ...}` envelope included).
          */
        def as[A: NativeConverter]: IO[FetchError, A] = ZIO.fromEither(kyoh3.FetchResponse.decoded[A](status, text))

    /** Read `body` once. Its elements are the decoded UTF-8 text of each chunk the server sent.
      * When the body breaks before its end (the callee's stream failed after its head, or the
      * connection closed), the stream fails with `FetchError.BodyFailed`. Interrupting the stream
      * stops the read; the rest of the body is cancelled when the caller's `Scope` closes.
      */
    final case class Streamed(status: Int, headers: Map[String, String], body: ZStream[Any, FetchError, String])
        extends FetchResponse
end FetchResponse
