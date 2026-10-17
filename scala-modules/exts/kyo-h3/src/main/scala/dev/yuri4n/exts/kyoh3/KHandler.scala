package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.h3.internal.{H3, H3Event, Nitro}
import dev.yuri4n.exts.kyoh3.internal.{Interop, Rendering, RequestLifecycle, StreamResponse, Supervised}
import kyo.{Abort, Async, Frame, Result, Scope, Sync, Var, `<`}

import scala.scalajs.js
import scala.util.control.NonFatal

/** Defines h3 handlers from Kyo programs.
  *
  * A handler is a function from the request snapshot to the response body: a Kleisli arrow
  * `Request => B < HandlerEffects`. The core runs it once per request, owns its scope and
  * cancellation, and writes the response head and body once, after the program and its finalizers
  * finish.
  */
object KHandler:
    /** The program receives the request snapshot and returns the body. */
    def define[B](program: Request => B < HandlerEffects)(using ResponseBody[B], Frame): EventHandler =
      define(ResponseHead.empty)(program)

    /** Same, with response defaults applied on success and on failure (e.g. `ResponseHead.noStore`). */
    def define[B](defaults: ResponseHead)(program: Request => B < HandlerEffects)(using
        body: ResponseBody[B],
        frame: Frame,
    ): EventHandler =
      val native: js.Function1[H3Event, js.Any] = event => RequestRuntime.run(event, defaults, program)
      EventHandler.fromNative(H3.defineEventHandler(native))

    /** Fails with an approved status and message. */
    def failWith(status: Int, message: String)(using Frame): Nothing < Abort[Throwable] =
      Abort.fail(HttpError(status, message))
end KHandler

/** One request: snapshot, program, scope, cancellation and the single write of head and body. */
private[kyoh3] object RequestRuntime:
    private enum Completion:
        case Value(head: ResponseHead, native: js.Any)
        case Failed(head: ResponseHead, error: Result.Error[Throwable])
        case Sent
        case Abandoned

    def run[B](event: H3Event, defaults: ResponseHead, program: Request => B < HandlerEffects)(using
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
            val supervised = new Supervised[Completion](work(event, defaults, program))
            lifecycle.setInterrupt(() => supervised.interrupt())
            supervised.onComplete { result =>
              lifecycle.unlisten()
              val response =
                try
                    if lifecycle.disconnected then lifecycle.claim()
                    else render(event, lifecycle, defaults, result)
                catch
                    case NonFatal(error) =>
                      if lifecycle.disconnected then lifecycle.claim() else defect(event, defaults, ResponseHead.empty, error)
              resolve(response)
            },
          )
    end run

    /** The worker program. Var runs outside Abort so a failure still sees the response head. */
    private def work[B](event: H3Event, defaults: ResponseHead, program: Request => B < HandlerEffects)(using
        body: ResponseBody[B],
        frame: Frame,
    ): Completion < (Async & Abort[Throwable] & Scope) =
      Sync.defer(Request.fromEvent(event)).map { request =>
        Var.runTuple(ResponseHead.empty)(
          Abort.run[Any](Sync.defer(program(request)).map(value => body.encode(value))),
        ).map { (head, result) =>
          Interop.typed(result).foldError(
            {
              case ResponseBody.Outgoing.Value(native)    => Completion.Value(head, native)
              case ResponseBody.Outgoing.Streamed(chunks) =>
                val commit = () => Rendering.writeHead(event, ResponseHead.forSuccess(defaults, head))
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
                    else Completion.Failed(head, failure),
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
        defaults: ResponseHead,
        result: Result[Throwable, Completion],
    ): js.Any =
      result.foldError(
        {
          case Completion.Value(head, native) =>
            val complete = ResponseHead.forSuccess(defaults, head)
            try
                Rendering.writeHead(event, complete)
                native
            catch
                case NonFatal(error) =>
                  Rendering.clearHead(event, complete)
                  defect(event, defaults, ResponseHead.empty, error)
          case Completion.Failed(head, error) => failure(event, defaults, head, error)
          case Completion.Sent                => lifecycle.claim()
          case Completion.Abandoned           => lifecycle.claim()
        },
        error => failure(event, defaults, ResponseHead.empty, error),
      )

    /** Only an approved SafeError failure reaches the client; everything else is an opaque 500. */
    private def failure(event: H3Event, defaults: ResponseHead, head: ResponseHead, error: Result.Error[Throwable]): js.Any =
      error.failureOrPanic match
          case safe: SafeError if error.isFailure =>
            Rendering.approved(safe) match
                case Some(status) =>
                  val complete = ResponseHead.forFailure(defaults, head, status, safe.headers)
                  try
                      Rendering.writeHead(event, complete)
                      Rendering.errorBody(Rendering.messages(safe))
                  catch
                      case NonFatal(writeError) =>
                        Rendering.clearHead(event, complete)
                        defect(event, defaults, ResponseHead.empty, writeError)
                case None         => defect(event, defaults, head, safe)
          case other                              => defect(event, defaults, head, other)

    private def defect(event: H3Event, defaults: ResponseHead, head: ResponseHead, error: Throwable): js.Any =
      val cause   = if error == null then IllegalStateException("Null handler failure") else error
      val details = Rendering.describe(cause)
      Rendering.logDefect(details)
      val complete = ResponseHead.forFailure(defaults, head, Status.InternalServerError, Nil)
      try Rendering.writeHead(event, complete)
      catch
          case NonFatal(_) =>
            Rendering.clearHead(event, complete)
            H3.setResponseStatus(event, 500)
      val response = Rendering.errorBody(Rendering.internalErrorBody)
      if Nitro.development(event) then response.updateDynamic("debug")(js.Dynamic.literal(cause = details))
      response
end RequestRuntime
