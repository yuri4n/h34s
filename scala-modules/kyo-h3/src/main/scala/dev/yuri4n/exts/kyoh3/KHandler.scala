package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.h3.internal.{H3, H3Event, HandlerMetadata, Nitro}
import dev.yuri4n.exts.kyoh3.internal.{Interop, NitroFetch, Rendering, RequestLifecycle, Supervised}
import kyo.{Abort, Async, Env, Fiber, Frame, Result, Scope, Sync, Var, `<`}
import org.getshaka.nativeconverter.NativeConverter

import scala.scalajs.js
import scala.util.control.NonFatal

// claude 2026-10-19: changed: streams are sent with Response.sendStream; $fetch is injected
/** Defines h3 handlers from Kyo programs.
  *
  * A handler is a function from the request snapshot to the response body: a Kleisli arrow
  * `Request => B < HandlerEffects`. The core runs it once per request, owns its scope and
  * cancellation, and writes the response head and body once, after the program and its finalizers
  * finish. A handler that streams sends the head and the stream itself with `Response.sendStream`
  * and returns its `Sent`.
  *
  * Wire format with the default options: a value body is `{"data": ...}`, a `Unit` body is a 204
  * without a body, a sent stream of strings is UTF-8 text, and errors are
  * `{"errors": [{"message": ...}]}`.
  */
object KHandler:
    /** The program receives the request snapshot and returns the body. Uses `HandlerOptions.default`. */
    def define[B](program: Request => B < HandlerEffects)(using ResponseBody[B], Frame): EventHandler =
      define(HandlerOptions.default)(program)

    /** Same, with options for every request: envelopes, response defaults (applied on success and
      * on failure, e.g. `ResponseHead.noStore`) and the default body limit.
      */
    def define[B](options: HandlerOptions)(program: Request => B < HandlerEffects)(using
        body: ResponseBody[B],
        frame: Frame,
    ): EventHandler =
      val native: js.Function1[H3Event, js.Any] = event => RequestRuntime.run(event, options, program)
      EventHandler.fromNative(HandlerMetadata.describe(H3.defineEventHandler(native), options, body.shape))

    /** Fails with an approved status and message. */
    def failWith(status: Int, message: String)(using Frame): Nothing < Abort[HttpError] =
      Abort.fail(HttpError(status, message))

    /** Runs `step` to its end even when the client disconnects, and makes the request wait for it.
      *
      * Use it for work that must not stop halfway, such as a commit. The step runs on its own fiber
      * that an interrupt cannot reach. The current scope (the request scope in a handler) waits for
      * the step before it runs earlier finalizers, so a connection that the step uses is released
      * only after the step, and the request settles only after both.
      *
      * Kyo's `Async.uninterruptible` alone protects the step but does not make the request wait:
      * after a disconnect the request settles and releases its resources while the step still runs.
      *
      * The step's result is returned as usual when the handler is not interrupted. After an
      * interrupt, the handler stops and nothing is written to the client.
      */
    def uninterruptible[A](step: => A < (Async & Abort[Throwable]))(using Frame): A < (Async & Abort[Throwable] & Scope) =
      // The release is registered in the step that starts the fiber, so an interrupt cannot land
      // between the two.
      Scope.acquireRelease(Fiber.initUnscoped(step))(_.getResult.unit).map(fiber => fiber.uninterruptible.map(_.get))

    // claude 2026-10-20: created: the public test helper (ADR-0006, Q10)
    /** Runs `handler` for `request` without h3, Nitro or a server, and gives the head that the
      * program set and its body: the returned value, or the elements that it sent with
      * `Response.sendStream`.
      *
      * It provides what `define` provides for a request: a request scope (closed before the result
      * is given), an empty response head, a transport that collects a sent stream, and `fetch` as
      * the server-side `$fetch` (by default `Fetch.unavailable`; pass `Fetch.fake(...)` to answer
      * calls). Provide your own services inside the function, as you do for `define`:
      *
      * {{{
      * KHandler.test(request => Env.run(fakeNotes)(NotesHandler.create(request)))(Request.fake(...))
      * }}}
      *
      * A failure of the program stays a failure of the result: run it with `Abort.run` to check an
      * error. Handler defaults, the envelope and the error rendering belong to `define` and are not
      * applied here.
      */
    def test[B](handler: Request => B < HandlerEffects)(request: Request, fetch: Fetch = Fetch.unavailable)(using
        Frame,
    ): TestResponse[B] < (Async & Abort[Throwable]) =
      Sync.defer(HandlerTransport.collecting()).map { transport =>
        Scope.run(Var.runTuple(ResponseHead.empty)(Env.run(transport)(Env.run(fetch)(Sync.defer(handler(request))))))
          .map { (head, value) =>
            Sync.defer(TestResponse(head, TestResponse.collected(transport.collected, value)))
          }
      }

    // claude 2026-10-19: created: the $fetch accessor (decision 2) over the request's Env[Fetch]
    /** The server-side `$fetch` of the current request, with Nuxt's `event.fetch` semantics: a path
      * that starts with `/` is answered in-process by Nitro, and the incoming headers and cookies
      * are forwarded.
      *
      * `raw` returns as soon as the head arrives. `text`, `as`, `get` and `post` read the whole body
      * and fail with `FetchError.Status` unless the status is 2xx. A `FetchError` is not a
      * `SafeError`: unhandled, it is an opaque 500.
      */
    @annotation.nowarn("id=E230")
    object $fetch:
        def raw(
            url: String,
            method: String = "GET",
            headers: Seq[(String, String)] = Nil,
            body: Option[FetchBody] = None,
        )(using Frame): FetchResponse < (Async & Abort[FetchError] & Scope & Env[Fetch]) =
          Env.use[Fetch](_.raw(FetchRequest(url, method, headers, body)))

        def text(
            url: String,
            method: String = "GET",
            headers: Seq[(String, String)] = Nil,
            body: Option[FetchBody] = None,
        )(using Frame): String < (Async & Abort[FetchError] & Scope & Env[Fetch]) =
          raw(url, method, headers, body).map(response => bodyText(response).map(text => successText(response.status, text)))

        def as[A: NativeConverter](
            url: String,
            method: String = "GET",
            headers: Seq[(String, String)] = Nil,
            body: Option[FetchBody] = None,
        )(using Frame): A < (Async & Abort[FetchError] & Scope & Env[Fetch]) =
          raw(url, method, headers, body).map(response =>
            bodyText(response).map(text => successText(response.status, text)).map(text => FetchResponse.decode[A](response.status, text)),
          )

        def get[A: NativeConverter](url: String, headers: Seq[(String, String)] = Nil)(using
            Frame,
        ): A < (Async & Abort[FetchError] & Scope & Env[Fetch]) =
          as[A](url, headers = headers)

        def post[A: NativeConverter](url: String, body: FetchBody, headers: Seq[(String, String)] = Nil)(using
            Frame,
        ): A < (Async & Abort[FetchError] & Scope & Env[Fetch]) =
          as[A](url, "POST", headers, Some(body))

        private def bodyText(response: FetchResponse)(using Frame): String < (Async & Scope) = response match
            case FetchResponse.Plain(_, _, text)    => text
            case FetchResponse.Streamed(_, _, body) => body.run.map(_.mkString)

        private def successText(status: Int, text: String)(using Frame): String < Abort[FetchError] =
          if status >= 200 && status <= 299 then text else Abort.fail(FetchError.Status(status, text))
    end $fetch
end KHandler

/** One request: snapshot, program, scope, cancellation and the single write of head and body. */
private[kyoh3] object RequestRuntime:
    private enum Completion:
        case Value(head: ResponseHead, native: js.Any)
        case Empty(head: ResponseHead)
        case Failed(head: ResponseHead, error: Result.Error[Throwable])
        case Sent

    def run[B](event: H3Event, options: HandlerOptions, program: Request => B < HandlerEffects)(using
        body: ResponseBody[B],
        frame: Frame,
    ): js.Promise[js.Any] =
      val lifecycle = new RequestLifecycle(event)
      lifecycle.listen()
      if lifecycle.disconnected then
          lifecycle.unlisten()
          js.Promise.resolve[js.Any](lifecycle.claim())
      else
          new js.Promise[js.Any]((resolve, _) =>
            // claude 2026-10-19: changed: one HandlerTransport per request; a socketless stream settles the h3 promise early
            // claude 2026-10-20: changed: the transport can end the connection after a failure without reporting a disconnect (ADR-0004)
            val transport  = new HandlerTransport(
              event,
              options,
              () => resolve(js.undefined),
              () =>
                lifecycle.unlisten()
                event.node.res.destroy(),
            )
            val supervised = new Supervised[Completion](work(transport, program))
            lifecycle.setInterrupt(() => supervised.interrupt())
            supervised.onComplete { result =>
              val clientLeft = lifecycle.closedByClient
              lifecycle.unlisten()
              val response =
                try
                    // The transport owns a sent response: write nothing more, but log a failure
                    // that happened after it (for example a second sendStream). A client that
                    // disconnected interrupted the program: that is not a failure to log.
                    if transport.sent then
                        if !clientLeft then afterSent(transport, result)
                        lifecycle.claim()
                    else if lifecycle.disconnected then lifecycle.claim()
                    else render(event, lifecycle, options, result)
                catch
                    case NonFatal(error) =>
                      if lifecycle.disconnected then lifecycle.claim() else defect(event, options, ResponseHead.empty, error)
              transport.markFinished()
              resolve(response)
            },
          )
    end run

    // claude 2026-10-19: changed: provides Env[HandlerTransport] and Env[Fetch]; a Sent body ends the request
    /** The worker program. Var runs outside Abort so a failure still sees the response head. */
    private def work[B](transport: HandlerTransport, program: Request => B < HandlerEffects)(using
        body: ResponseBody[B],
        frame: Frame,
    ): Completion < (Async & Abort[Throwable] & Scope) =
      val event   = transport.event
      val options = transport.options
      Sync.defer {
        // Captured at request start: Nitro sets event.fetch in onRequest, before any route handler.
        (Request.fromEvent(event, options.bodyLimit, options.requireJsonContentType), new NitroFetch(event))
      }.map { (request, fetch) =>
        Var.runTuple(ResponseHead.empty)(
          Abort.run[Any](
            Env.run(transport)(Env.run(fetch)(
              Sync.defer(program(request)),
            )).map(value => body.encode(value)),
          ),
        ).map { (head, result) =>
          Interop.typed(result).foldError(
            {
              case ResponseBody.Outgoing.Value(native)    => Completion.Value(head, native)
              case ResponseBody.Outgoing.Empty            => Completion.Empty(head)
              case ResponseBody.Outgoing.Sent(sent)       =>
                if sent.owner eq transport then Completion.Sent
                else Completion.Failed(head, Result.Panic(IllegalStateException("The handler returned a Sent value of another request")))
            },
            failure => Completion.Failed(head, failure),
          )
        }
      }

    // claude 2026-10-19: created: a failure after the response was sent cannot reach the client; log it
    // claude 2026-10-20: changed: also logs a stream failure that the program recovered from, once (ADR-0004, Q3)
    /** Logs one failure: the program's own, or else the sent stream's failure that the program
      * recovered from. The stream's failure usually is the program's failure, so it is not logged
      * twice.
      */
    private def afterSent(transport: HandlerTransport, result: Result[Throwable, Completion]): Unit =
      def log(error: Throwable): Unit = Rendering.logDefect(Rendering.describe(error))
      result.foldError(
        {
          case Completion.Failed(_, error) => log(error.failureOrPanic)
          case _                           => transport.streamFailure.foreach(log)
        },
        error => log(error.failureOrPanic),
      )

    private def render(
        event: H3Event,
        lifecycle: RequestLifecycle,
        options: HandlerOptions,
        result: Result[Throwable, Completion],
    ): js.Any =
      result.foldError(
        {
          // claude 2026-10-19: changed: a body that cannot be rendered (NaN, infinity) is a defect under the failure rule
          case Completion.Value(head, native) =>
            val complete = ResponseHead.forSuccess(options.defaults, head)
            val rendered =
              try Right(Rendering.successBody(options.success, native))
              catch case NonFatal(error) => Left(error)
            rendered match
                case Right((body, contentType)) =>
                  success(event, options, contentType.fold(complete)(Rendering.withDefaultContentType(event, complete, _)), body)
                case Left(error)                => defect(event, options, head, error)
          case Completion.Empty(head)         =>
            val complete = ResponseHead.forSuccess(options.defaults, head)
            success(event, options, complete.copy(status = complete.status.orElse(Some(Status.NoContent))), Rendering.noContent)
          case Completion.Failed(head, error) => failure(event, options, head, error)
          case Completion.Sent                => lifecycle.claim()
        },
        error => failure(event, options, ResponseHead.empty, error),
      )

    private def success(event: H3Event, options: HandlerOptions, complete: ResponseHead, response: js.Any): js.Any =
      try
          Rendering.writeHead(event, complete)
          response
      catch
          case NonFatal(error) =>
            Rendering.clearHead(event, complete)
            defect(event, options, ResponseHead.empty, error)

    /** Only an approved SafeError failure reaches the client; everything else is an opaque 500. */
    private def failure(event: H3Event, options: HandlerOptions, head: ResponseHead, error: Result.Error[Throwable]): js.Any =
      error.failureOrPanic match
          case safe: SafeError if error.isFailure =>
            Rendering.approved(safe) match
                case Some((status, entries)) =>
                  val complete = ResponseHead.forFailure(options.defaults, head, status, safe.headers)
                  try
                      Rendering.writeHead(event, complete)
                      Rendering.errorBody(options.errors, entries)
                  catch
                      case NonFatal(writeError) =>
                        Rendering.clearHead(event, complete)
                        defect(event, options, ResponseHead.empty, writeError)
                case None                    => defect(event, options, head, safe)
          case other                              => defect(event, options, head, other)

    /** An opaque 500. Diagnostics only in development or debug mode (set by the Nitro plugin). */
    private def defect(event: H3Event, options: HandlerOptions, head: ResponseHead, error: Throwable): js.Any =
      val cause   = if error == null then IllegalStateException("Null handler failure") else error
      val details = Rendering.describe(cause)
      Rendering.logDefect(details)
      val complete = ResponseHead.forFailure(options.defaults, head, Status.InternalServerError, Nil)
      try Rendering.writeHead(event, complete)
      catch
          case NonFatal(_) =>
            Rendering.clearHead(event, complete)
            H3.setResponseStatus(event, 500)
      Rendering.internalErrorBody(options.errors, Option.when(Nitro.diagnostics(event))(details))
end RequestRuntime
