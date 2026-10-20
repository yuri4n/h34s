package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.h3.internal.{H3, H3Event, HandlerMetadata, Nitro}
import dev.yuri4n.exts.kyoh3.internal.{Interop, Rendering, RequestLifecycle, StreamResponse, Supervised}
import kyo.{Abort, Async, Fiber, Frame, Result, Scope, Sync, Var, `<`}

import scala.scalajs.js
import scala.util.control.NonFatal

/** Defines h3 handlers from Kyo programs.
  *
  * A handler is a function from the request snapshot to the response body: a Kleisli arrow
  * `Request => B < HandlerEffects`. The core runs it once per request, owns its scope and
  * cancellation, and writes the response head and body once, after the program and its finalizers
  * finish.
  *
  * Wire format with the default options: a value body is `{"data": ...}`, a `Unit` body is a 204
  * without a body, a stream of strings is sent as UTF-8 text, and errors are
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
end KHandler

/** One request: snapshot, program, scope, cancellation and the single write of head and body. */
private[kyoh3] object RequestRuntime:
    private enum Completion:
        case Value(head: ResponseHead, native: js.Any)
        case Empty(head: ResponseHead)
        case Failed(head: ResponseHead, error: Result.Error[Throwable])
        case Sent
        case Abandoned

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
            val supervised = new Supervised[Completion](work(event, options, program))
            lifecycle.setInterrupt(() => supervised.interrupt())
            supervised.onComplete { result =>
              lifecycle.unlisten()
              val response =
                try
                    if lifecycle.disconnected then lifecycle.claim()
                    else render(event, lifecycle, options, result)
                catch
                    case NonFatal(error) =>
                      if lifecycle.disconnected then lifecycle.claim() else defect(event, options, ResponseHead.empty, error)
              resolve(response)
            },
          )
    end run

    /** The worker program. Var runs outside Abort so a failure still sees the response head. */
    private def work[B](event: H3Event, options: HandlerOptions, program: Request => B < HandlerEffects)(using
        body: ResponseBody[B],
        frame: Frame,
    ): Completion < (Async & Abort[Throwable] & Scope) =
      Sync.defer(Request.fromEvent(event, options.bodyLimit, options.requireJsonContentType)).map { request =>
        Var.runTuple(ResponseHead.empty)(
          Abort.run[Any](Sync.defer(program(request)).map(value => body.encode(value))),
        ).map { (head, result) =>
          Interop.typed(result).foldError(
            {
              case ResponseBody.Outgoing.Value(native)    => Completion.Value(head, native)
              case ResponseBody.Outgoing.Empty            => Completion.Empty(head)
              case ResponseBody.Outgoing.Streamed(chunks) =>
                val committed = ResponseHead.forSuccess(options.defaults, head)
                val streamHead = Rendering.withDefaultContentType(event, committed, Rendering.textContentType)
                var applied    = false
                val commit     = () =>
                  applied = true
                  Rendering.writeHead(event, streamHead)
                Abort.run[Any](StreamResponse.send(event, chunks, commit)).map(result => Interop.typed(result).foldError(
                  _ => Completion.Sent,
                  failure =>
                    if headersSent(event) then
                        Sync.defer {
                          // The client already has a status and part of the body: end the
                          // connection so the response cannot look complete.
                          event.node.res.destroy()
                          Rendering.logDefect(Rendering.describe(failure.failureOrPanic))
                          Completion.Abandoned
                        }
                    else
                        Sync.defer {
                          // The head may be applied already (before the first chunk) but not sent:
                          // the failure response must not carry success-only headers or cookies.
                          if applied then Rendering.clearHead(event, streamHead)
                          Completion.Failed(head, failure)
                        },
                ))
            },
            failure => Completion.Failed(head, failure),
          )
        }
      }

    private def headersSent(event: H3Event): Boolean =
      Nitro.isTrue(event.node.res.asInstanceOf[js.Dynamic].headersSent)

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
          case Completion.Abandoned           => lifecycle.claim()
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
