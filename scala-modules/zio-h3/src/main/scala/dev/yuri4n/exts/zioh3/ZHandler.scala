package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.kyoh3.internal.EventStreams
import dev.yuri4n.exts.kyoh3.{KHandler, ResponseBody}
import dev.yuri4n.exts.zioh3.internal.ZioRequest
import org.getshaka.nativeconverter.NativeConverter
import zio.prelude.NonEmptyList
import zio.stream.ZStream
import zio.{IO, Runtime, Scope, ZEnvironment, ZIO}

// claude 2026-10-19: changed: streams are sent with ZHandler.sendStream
// claude 2026-10-20: changed: streams are sent with Response.sendStream; ZHandler.sendStream is deleted (ADR-0004, Q8)
/** Defines h3 handlers from ZIO programs.
  *
  * A handler is a function from the request snapshot to a ZIO program that returns the response
  * body: `Request => ZIO[HandlerEnv, ErrorResponse | Throwable, B]`. h34s runs it once per request on
  * the given ZIO `Runtime`, supplies `HandlerEnv` (response head state, request scope and the
  * transport), and writes the response once, after the program and its finalizers finish. A
  * handler that streams sends the head and the stream itself with `Response.sendStream` and returns
  * its `Sent`.
  *
  * Provide your own services before you pass the program, with ordinary ZIO (`provide`,
  * `provideSomeLayer`, `ZShared.provide`). The wire format is the one of `KHandler`: a value body is
  * `{"data": ...}`, a `Unit` body is a 204 without a body, a sent `ZStream` is UTF-8 text, and errors
  * are `{"errors": [{"message": ...}]}`.
  */
object ZHandler:
    /** The program receives the request snapshot and returns the body. Uses `HandlerOptions.default`.
      * Pass a `Runtime` as a given to run the program on it (default: `Runtime.default`).
      */
    def define[B](program: Request => ZIO[HandlerEnv, ErrorResponse | Throwable, B])(using
        body: ZResponseBody[B],
        runtime: Runtime[Any] = Runtime.default,
    ): EventHandler =
      Configured(HandlerOptions.default)(program)

    /** Same, with options for every request: envelopes, response defaults (applied on success and
      * on failure, for example `ResponseHead.noStore`) and the default body limit.
      *
      * {{{
      * ZHandler.define(HandlerOptions(defaults = ResponseHead.noStore)): request =>
      *   ...
      * }}}
      */
    def define(options: HandlerOptions): Configured = Configured(options)

    /** Fails with approved messages and a status. Each message becomes one error entry. */
    def failWith(status: Int, msg: String, msgs: String*): IO[ErrorResponse, Nothing] =
      ZIO.fail(ErrorResponse(NonEmptyList(msg, msgs*), status))

    // claude 2026-10-20: created: the public test helper (ADR-0006, Q10); after sendStream, the head at the call; the fetch parameter, in the shape of KHandler.test (ADR-0005 gap 6)
    /** Runs `handler` for `request` without h3, Nitro or a server, and gives the head that the
      * program set and its body: the returned value, or the elements that it sent with
      * `Response.sendStream`. After `sendStream`, the head is the one at the call, as on the wire: a
      * change after the call does not reach the client, so it is not in the result.
      *
      * It provides what `define` provides for a request: the `ZState[ResponseHead]`, a request
      * `Scope` (closed before the result is given; a failing finalizer is logged, as in `define`),
      * a transport that collects a sent stream, and `fetch` as the server-side `$fetch` (by default
      * `Fetch.unavailable`; pass `Fetch.fake(...)` to answer calls). Provide your own services
      * inside the function, as you do for `define`:
      *
      * {{{
      * ZHandler.test(request => NotesHandler.create(request).provideSomeLayer[HandlerEnv](notes))(Request.fake(...))
      * }}}
      *
      * The program's typed error stays in the error channel, so `.either` shows it. Handler
      * defaults, the envelope and the error rendering belong to `define` and are not applied here.
      */
    def test[E, B](handler: Request => ZIO[HandlerEnv, E, B])(request: Request, fetch: Fetch = Fetch.unavailable): IO[E, TestResponse[B]] =
      ZIO.suspendSucceed {
        val transport = HandlerTransport.collecting()
        ZIO.acquireReleaseExitWith(zio.Scope.make)((scope, exit) => ZioRequest.closeAndLog(scope, exit)) { scope =>
          ZIO.stateful[zio.Scope & HandlerTransport & Fetch](ResponseHead.empty)(
            ZIO.suspendSucceed(handler(request)).zip(Response.head),
          ).provideEnvironment(ZEnvironment[zio.Scope, HandlerTransport, Fetch](scope, transport, fetch))
        }.map((value, head) => TestResponse(transport.sentHead.getOrElse(head), TestResponse.collected(transport.collected, value)))
      }

    // claude 2026-10-20: created: the ZIO $fetch accessor over the Fetch of HandlerEnv (ADR-0005 decision 2 and gap 6); events (ADR-0007)
    /** The server-side `$fetch` of the current request.
      *
      *   - **In-process paths.** A path that starts with `/` is answered in-process by Nitro, and
      *     the incoming headers and cookies are forwarded, as Nuxt does. h34s puts the app's
      *     `app.baseURL` in front of the path, as Nuxt's `$fetch` does (a path that already starts
      *     with it stays as it is). The callee gets only the caller's `_platform` and `waitUntil`
      *     context entries, never the rest of the caller's `event.context`.
      *   - **Absolute URLs.** An absolute URL gets only the `headers` you pass, and the content
      *     type and length of the `body` you send. The user's cookies and `authorization` never
      *     reach another host. Nuxt's `event.$fetch` forwards them to any host; h34s does not.
      *   - **Plain or streamed.** `raw` gives `FetchResponse.Streamed` when the response carries
      *     the `x-h34s-stream` marker (every `Response.sendStream` sets it) or a stream content type
      *     (`text/event-stream`, `application/x-ndjson`), and `FetchResponse.Plain` otherwise. Its
      *     body is a `ZStream` that fails with `FetchError.BodyFailed` when it breaks after the head.
      *   - **Raw JSON.** `as`, `get` and `post` decode the JSON as it arrives, envelope included: an
      *     h34s route with the default options answers `{"data": ...}`, so model the envelope, for
      *     example `final case class GreetingData(data: Greeting)`. The typed route call (issue
      *     #46) will remove the envelope for you, because only the route knows its options.
      *   - **Server-sent events.** `events[E]` reads a `text/event-stream` response as a `ZStream`
      *     of `E`, each event's `data` decoded as JSON; `events(request)` gives the `ServerEvent`
      *     values themselves.
      *
      * `raw` returns as soon as the head arrives, and its body lives in the current `Scope` (the
      * request scope, or a `ZIO.scoped` block): when the scope closes, h34s cancels a body that you
      * did not read to its end and waits for the callee's cleanup. `text`, `as`, `get` and `post`
      * read the whole body in their own scope and fail with `FetchError.Status` unless the status is
      * 2xx. A `FetchError` is not a `SafeError`: unhandled, it is an opaque 500.
      */
    @annotation.nowarn("id=E230")
    object $fetch:
        def raw(
            url: String,
            method: String = "GET",
            headers: Seq[(String, String)] = Nil,
            body: Option[FetchBody] = None,
        ): ZIO[Fetch & Scope, FetchError, FetchResponse] =
          ZIO.serviceWithZIO[Fetch](_.raw(FetchRequest(url, method, headers, body)))

        def text(
            url: String,
            method: String = "GET",
            headers: Seq[(String, String)] = Nil,
            body: Option[FetchBody] = None,
        ): ZIO[Fetch, FetchError, String] =
          ZIO.scoped[Fetch](raw(url, method, headers, body).flatMap(response => successText(response)))

        def as[A: NativeConverter](
            url: String,
            method: String = "GET",
            headers: Seq[(String, String)] = Nil,
            body: Option[FetchBody] = None,
        ): ZIO[Fetch, FetchError, A] =
          ZIO.scoped[Fetch](raw(url, method, headers, body).flatMap(response =>
            successText(response).flatMap(text => FetchResponse.Plain(response.status, response.headers, text).as[A]),
          ))

        /** A GET that decodes the 2xx body as `A`, as the JSON arrives: from an h34s route with the
          * default options, `A` must model the `{"data": ...}` envelope.
          */
        def get[A: NativeConverter](url: String, headers: Seq[(String, String)] = Nil): ZIO[Fetch, FetchError, A] =
          as[A](url, headers = headers)

        /** A POST of `body` that decodes the 2xx body as `A`, as the JSON arrives: from an h34s route
          * with the default options, `A` must model the `{"data": ...}` envelope.
          */
        def post[A: NativeConverter](url: String, body: FetchBody, headers: Seq[(String, String)] = Nil): ZIO[Fetch, FetchError, A] =
          as[A](url, "POST", headers, Some(body))

        // claude 2026-10-20: created: typed server-sent events, the caller side of sendEvents (ADR-0007)
        /** Sends the request with `accept: text/event-stream` (unless `headers` names `accept`) and
          * gives its server-sent events as a `ZStream` of `E`, as soon as the head arrives. Each
          * event's `data` is decoded as JSON with the `NativeConverter`, whatever its type: read a
          * stream that mixes other data with `events(request)`.
          *
          * The response must be 2xx (else `FetchError.Status`, with the body of a plain response)
          * with the content type `text/event-stream` (else `FetchError.NotEvents`, without reading
          * the body). The stream fails with `FetchError.Decode`, which names the event by its
          * position and type, at the first event whose `data` is not JSON for `E`, and with
          * `FetchError.BodyFailed` when the body breaks. The body lives in the current `Scope`, as
          * the body of `raw` does: when the scope closes, h34s cancels a body that you did not read
          * to its end and waits for the callee's cleanup.
          */
        def events[E: NativeConverter](
            url: String,
            method: String = "GET",
            headers: Seq[(String, String)] = Nil,
            body: Option[FetchBody] = None,
        ): ZIO[Fetch & Scope, FetchError, ZStream[Any, FetchError, E]] =
          opened(FetchRequest(url, method, headers, body)).map((status, events) =>
            events.zipWithIndex.mapZIO((event, index) => ZIO.fromEither(EventStreams.decodedEvent[E](status, event, index + 1))),
          )

        // claude 2026-10-20: created: untyped server-sent events, for a route without a model such as a JavaScript route (ADR-0007)
        /** Sends `request` (with `accept: text/event-stream` unless it names `accept`) and gives its
          * server-sent events as `ServerEvent` values, with the rules of `events[E]` but without
          * decoding `data`. Use it for a route that has no model, for example a JavaScript route
          * that uses h3's `createEventStream`.
          */
        def events(request: FetchRequest): ZIO[Fetch & Scope, FetchError, ZStream[Any, FetchError, ServerEvent]] =
          opened(request).map((_, events) => events)

        /** The response's status and its decoded events, or the reason it is not an event stream. */
        private def opened(request: FetchRequest): ZIO[Fetch & Scope, FetchError, (Int, ZStream[Any, FetchError, ServerEvent])] =
          ZIO.serviceWithZIO[Fetch](_.raw(EventStreams.request(request))).flatMap { response =>
            val plainText = response match
                case FetchResponse.Plain(_, _, text) => Some(text)
                case _: FetchResponse.Streamed       => None
            EventStreams.refused(response.status, response.headers, plainText) match
                case Some(error) => ZIO.fail(error)
                case None        =>
                  val text = response match
                      case FetchResponse.Plain(_, _, text)    => ZStream(text)
                      case FetchResponse.Streamed(_, _, body) => body
                  ZIO.succeed((response.status, decoded(text)))
          }

        /** The events of a body of text chunks, decoded as they arrive. */
        private def decoded(body: ZStream[Any, FetchError, String]): ZStream[Any, FetchError, ServerEvent] =
          body.chunks
            .mapAccum(ServerEvent.Decoder.initial)((state, chunk) => EventStreams.feedAll(state, chunk))
            .flattenIterables

        /** The whole body, or `FetchError.Status` unless the status is 2xx. */
        private def successText(response: FetchResponse): IO[FetchError, String] =
          val text = response match
              case FetchResponse.Plain(_, _, text)    => ZIO.succeed(text)
              case FetchResponse.Streamed(_, _, body) => body.runCollect.map(_.mkString)
          text.flatMap(text => if response.ok then ZIO.succeed(text) else ZIO.fail(FetchError.Status(response.status, text)))
    end $fetch

    /** Handlers that share one set of options. */
    final class Configured private[ZHandler] (options: HandlerOptions):
        def apply[B](program: Request => ZIO[HandlerEnv, ErrorResponse | Throwable, B])(using
            body: ZResponseBody[B],
            runtime: Runtime[Any] = Runtime.default,
        ): EventHandler =
          given ResponseBody[B] = ZResponseBody.core(body, runtime)
          KHandler.define[B](options)(request => ZioRequest.run(runtime)(program(request)))
end ZHandler
