package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.h3.given
import dev.yuri4n.exts.h3.{AnyEvHandlerRes, CommonEvHandler, H3Event1}
import dev.yuri4n.exts.ntcvzio.given
import dev.yuri4n.exts.zioh3.streamconversion.toReadableStream
import org.getshaka.nativeconverter.{NativeConverter, fromNative}
import org.scalablytyped.runtime.StringDictionary
import org.scalajs.dom
import org.scalajs.dom.Response
import typings.h3.mod.{
  EventHandlerObject,
  EventHandlerRequest,
  defineEventHandler as h3DefineEventHandler,
  getQuery as h3GetQuery,
  getRouterParams as h3GetRouterParams,
  parseCookies as h3ParseCookies,
  readBody as h3ReadBody,
  sendNoContent as h3SendNoContent,
  sendStream as h3SendStream,
  setResponseStatus as h3SetResponseStatus,
}
import typings.std.{PromiseLike, Record}
import zio.json.JsonEncoder
import zio.prelude.NonEmptyList
import zio.stream.{Stream, ZStream}
import zio.{Cause, IO, Promise, RIO, Runtime, Task, UIO, URIO, Unsafe, ZIO, ZLayer}

import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.util.control.NoStackTrace

import NativeConverter.given

type TaskStream[+A]   = Stream[Throwable, A]
type RStream[-R, +A]  = ZStream[R, Throwable, A]
type URStream[-R, +A] = ZStream[R, Nothing, A]

extension (j: js.Any) def fromNative_[A: NativeConverter]: Task[A] = ZIO.attempt(j.fromNative[A])

// extension [R, E, A](z: ZIO[R, E, A])
//   def toPromiseJS(using toE: E <:< Throwable): RIO[R, js.Promise[A]] =
//     ZIO.executor
//       .map(_.asExecutionContext)
//       .flatMap(implicit ec => z.toFuture.map(_.toJSPromise))
//     // z.toFuture.flatMap(f => ZIO.fromFuture(implicit ec => Future(f.toJSPromise)))

// extension (_z: ZIO.type)
//   def fromPromiseJS[R, A](p: js.Promise[A]): RIO[R, A] =
//     ZIO.fromFuture(implicit ec => p.toFuture)

final case class SH3Event(private val event: H3Event1):
    export event.{context, handled, headers, method, node, path, toJSON, web}

    /** Sends a Web `Response` and returns the proof that the response was sent.
      *
      * Changed by Claude Code on 2026-10-13: returns [[Responded]] instead of `Unit`.
      */
    def respondWith(response: Response): Task[Responded] =
      respondWithZIO(ZIO.succeed(response))

    /** Sends a Web `Response` produced by an effect and returns the proof that the response was
      * sent.
      *
      * Changed by Claude Code on 2026-10-13: returns [[Responded]] instead of `Unit`, and a
      * `Response` whose streamed body fails after its first chunk now ends the response the same
      * way `sendStream` does: the socket is destroyed and this effect fails with the error.
      */
    def respondWithZIO[E](
        response: IO[E, Response],
    )(using toT: E <:< Throwable): IO[E | Throwable, Responded] =
        val sent: IO[E | Throwable, Unit] =
          response.toPromiseJS.flatMap: promise =>
              ZIO.fromFuture: //
                  implicit ec =>
                      event
                        .respondWith(promise.asInstanceOf[PromiseLike[Response]])
                        .toFuture
        sent.tapError(_ => terminateStartedResponse).as(Responded.value)
    end respondWithZIO

    /** Ends the response with no body and returns the proof that the response was sent.
      *
      * Created by Claude Code on 2026-10-13.
      *
      * h3 writes the head and ends the response at once. Without a status, h3 uses 204 unless the
      * handler set another non-200 status before, which h3 keeps. If the response already started,
      * nothing is written: this logs one `[zio-h3]` warning, and the proof is still true, because
      * the response was sent.
      */
    def sendNoContent: Task[Responded] =
      noContent(None)

    /** Ends the response with no body and the given status. See [[sendNoContent]]. Created by Claude
      * Code on 2026-10-13.
      */
    def sendNoContent(status: Int): Task[Responded] =
      noContent(Some(status))

    // Created by Claude Code on 2026-10-13: the shared body of the two `sendNoContent` forms. The
    // response state is read when the effect runs, not when the value is built.
    private def noContent(status: Option[Int]): Task[Responded] =
      ZIO.suspendSucceed:
          if event.handled then
              ZIOH3.Console
                .warn(
                  s"[zio-h3] $route: sendNoContent was called after the response was sent. Nothing was written.",
                )
                .ignore
                .as(Responded.value)
          else
              ZIO
                .attempt(status.fold(h3SendNoContent(event))(h3SendNoContent(event, _)))
                .as(Responded.value)

    def setResponseStatus(status: Int): Task[Unit] =
      ZIO.attempt(
        h3SetResponseStatus(event, status),
      ) *> (if status < 200 || status >= 400 then
                ZIOH3.Console.error(s"Response status set to $status")
            else ZIOH3.Console.log(s"Response status set to $status"))

    def readBody[T: NativeConverter]: Task[T] =
      ZIO
        .fromFuture(implicit ec => h3ReadBody[T, H3Event1, js.Any](event).toFuture)
        .flatMap(_.fromNative_[T])

    def getQuery: Task[Map[String, String]] =
      ZIO
        .attempt(h3GetQuery[Nothing, H3Event1, StringDictionary[String]](event))
        .tap(ZIOH3.Console.log("Raw query:", _))
        .map(_.toMap)

    def getQuery[T](using cv: Conversion[Map[String, String], T]): Task[T] =
      getQuery.map(cv)

    def getRouterParams: Task[Map[String, String]] =
      ZIO
        .attempt(h3GetRouterParams(event))
        .tap(ZIOH3.Console.log("Raw router params:", _))
        .map(maybeRecord => maybeRecord.toOption.map(_.toMap).getOrElse(Map.empty))

    def getRouterParam(
        paramName: String,
    ): IO[Throwable | SH3Event.MissingRouterParamError, String] =
      getRouterParams.flatMap(params =>
        ZIO
          .fromOption(params.get(paramName))
          .mapError(_ => SH3Event.MissingRouterParamError(paramName)),
      )

    /** Sends a ZIO stream as the HTTP response body.
      *
      * Changed by Claude Code on 2026-10-13: a failure after the first chunk now ends the response,
      * and a client disconnect now interrupts the source stream.
      *
      * h3 pipes the stream into the Node response and ends the response when the stream closes.
      * Behavior at the boundaries of the stream:
      *   - The source fails before the first chunk: no headers were sent. This effect fails with
      *     the source's error, and `mkHandler` sends the usual error response.
      *   - The source fails after the first chunk: the status and the chunks sent so far are
      *     already with the client, so no error body can follow. This method destroys the response
      *     socket. The client sees an incomplete chunked body and a transport error instead of a
      *     response that never ends. This effect then fails with the source's error, and
      *     `mkHandler` logs it without writing anything.
      *   - The client disconnects before the stream ends: Node emits `close` on the response, the
      *     source stream is interrupted at its next element, and its finalizers run. This effect
      *     then succeeds with [[Responded]], because the response is over for everyone.
      *
      * Remaining limits of this transport:
      *   - Each native chunk reaches Node's `response.write` unchanged, so it must be a string, a
      *     `Buffer`, or a `Uint8Array`. The compiler does not check this.
      *   - h3 sets no `Content-Type` for a streamed body. Set it before this call if clients need
      *     one.
      *   - Finish the handler with the value this method returns. It is a [[Responded]] proof,
      *     which `mkHandler` turns into `undefined`; h3 then writes nothing more. A handler that
      *     ends with `()` after this call does not compile, and a body value returned after this
      *     call is dropped with a `[zio-h3]` warning instead of reaching Node. (Changed by Claude
      *     Code on 2026-10-13: this bullet.)
      *   - The source runs eagerly. Web Streams backpressure does not reach it.
      */
    def sendStream[A: NativeConverter](
        stream: TaskStream[A],
    ): Task[Responded] =
      // Changed by Claude Code on 2026-10-13: a second send writes nothing. h3's `sendStream` has no
      // guard of its own and would write into the ended response. The state is read when the effect
      // runs.
      ZIO.suspendSucceed:
          if event.handled then
              ZIOH3.Console
                .warn(s"[zio-h3] $route: sendStream was called after the response was sent. Nothing was written.")
                .ignore
                .as(Responded.value)
          else
              for
                  runtime                <- ZIO.runtime
                  given ExecutionContext <- ZIO.executor.map(_.asExecutionContext)
                  clientClosed           <- Promise.make[Nothing, Unit]
                  _                      <- ZIO.attempt(onResponseClose(() => completeFromJS(runtime, clientClosed)))
                  readable                = stream.interruptWhen(clientClosed.await).toReadableStream(runtime)
                  _                      <- ZIO
                                              .fromPromiseJS(h3SendStream(event, readable))
                                              .tapError(_ => terminateStartedResponse)
              yield Responded.value // Changed by Claude Code on 2026-10-13: the proof, not `()`.

    /** The request's method and path, for log lines. Created by Claude Code on 2026-10-13. */
    private def route: String = s"${event.method} ${event.path}"

    /** Registers `callback` to run once when the Node response closes. Node emits `close` after a
      * normal end and after a client disconnect. Environments without a Node response object, such
      * as workers, have no `once` method; this method then does nothing.
      *
      * Created by Claude Code on 2026-10-13.
      */
    private def onResponseClose(callback: () => Unit): Unit =
        val res = event.node.res.asInstanceOf[js.Dynamic]
        if js.typeOf(res.once) == "function" then
            res.once("close", js.Any.fromFunction0(callback))
            ()
    end onResponseClose

    /** Completes `promise` from a JavaScript callback. `Promise.succeed` finishes synchronously, so
      * `unsafe.run` does not block on Scala.js.
      *
      * Created by Claude Code on 2026-10-13.
      */
    private def completeFromJS(runtime: Runtime[Any], promise: Promise[Nothing, Unit]): Unit =
      Unsafe.unsafe { implicit unsafe =>
          runtime.unsafe.run(promise.succeed(()))
          ()
      }

    /** Destroys the response socket if the response already started. A chunked body without its
      * final chunk tells the client that the body is incomplete. Before the first chunk, this does
      * nothing, so the caller can still send an error response.
      *
      * Created by Claude Code on 2026-10-13.
      */
    private def terminateStartedResponse: UIO[Unit] =
      ZIO.succeed:
          if event.handled then
              event.node.res.asInstanceOf[js.Dynamic].destroy()
              ()

    /** Calls Nitro's `event.$fetch` and decodes the resolved value.
      *
      * Changed by Claude Code on 2026-10-13: awaits the promise before decoding. Before, the
      * converter ran on the promise object itself and the effect failed on every call.
      */
    def `$fetch`[A: NativeConverter](url: String): Task[A] =
      ZIO
        .fromPromiseJS(
          event
            .asInstanceOf[js.Dynamic]
            .`$fetch`(url.asInstanceOf[js.Any])
            .asInstanceOf[js.Promise[js.Any]],
        )
        .flatMap(_.fromNative_[A])

    def parseCookies: Task[Map[String, String]] =
      ZIO
        .attempt:
            h3ParseCookies(event)
        .map(_.toMap[String, String])
end SH3Event

object SH3Event:
    final case class MissingRouterParamError(paramName: String) extends SafeError:
        val message: String = s"Missing router parameter: $paramName"

type ZHandler[-R, +E, +A] = ZIO[SH3Event & R, E, A]
type Handler[+E, +A]      = ZHandler[Any, E, A]
type TaskHandler[+A]      = Handler[Throwable, A]
type URHandler[-R, +A]    = ZHandler[R, Nothing, A]
type UHandler[+A]         = ZHandler[Any, Nothing, A]
type RHandler[-R, +A]     = ZHandler[R, Throwable, A]

object ZHandler:

    def readBody[A: NativeConverter]: TaskHandler[A] =
      ZIO.serviceWithZIO[SH3Event](_.readBody[A])

    def setResponseStatus(status: Int): TaskHandler[Unit] =
      ZIO.serviceWithZIO[SH3Event]:
          _.setResponseStatus(status)

    def failWith(status: Int, msg: String, msgs: String*): Handler[ErrorResponse, Nothing] =
      setResponseStatus(status).! *> ZIO.fail(ErrorResponse(NonEmptyList(msg, msgs*)))

    def getEvent: URIO[SH3Event, SH3Event] = ZIO.service[SH3Event]

    def getQuery: TaskHandler[Map[String, String]] =
      ZIO.serviceWithZIO[SH3Event](_.getQuery)

    def getQuery[T: Conversion[Map[String, String], *]](using
        ec: ExecutionContext,
    ): TaskHandler[T] =
      ZIO.serviceWithZIO[SH3Event](_.getQuery[T])

    def getRouterParams: Handler[GetRouterParamsError, Map[String, String]] =
      ZIO.serviceWithZIO[SH3Event](_.getRouterParams.mapError(GetRouterParamsError.apply))

    def getRouterParam(
        paramName: String,
    ): Handler[SH3Event.MissingRouterParamError | GetRouterParamsError, String] =
      ZIO.serviceWithZIO[SH3Event](
        _.getRouterParam(paramName)
          .mapError:
              case e: SH3Event.MissingRouterParamError => e
              case t: Throwable                        => GetRouterParamsError(t),
      )

    final case class GetRouterParamsError(underlying: Throwable) extends SafeError:
        val message: String                             = "Failed to get router parameters"
        override val maybeUnderlying: Option[Throwable] = Some(underlying)

    /** Sends `stream` as the response body. See [[SH3Event.sendStream]] for the accepted chunk
      * types, what happens when the source fails or the client disconnects, and the remaining
      * limits.
      *
      * Changed by Claude Code on 2026-10-13: added this scaladoc, then pointed it at the failure
      * and disconnect behavior, then changed the result to [[Responded]].
      */
    def sendStream[A: NativeConverter](stream: TaskStream[A]): TaskHandler[Responded] =
      ZIO.serviceWithZIO[SH3Event](_.sendStream(stream))

    /** Sends a Web `Response`. The result is the proof that the response was sent; finish the
      * handler with it. Created by Claude Code on 2026-10-13.
      */
    def respondWith(response: Response): TaskHandler[Responded] =
      ZIO.serviceWithZIO[SH3Event](_.respondWith(response))

    /** Ends the response with no body, status 204 unless the handler set another non-200 status.
      * The result is the proof that the response was sent; finish the handler with it. See
      * [[SH3Event.sendNoContent]]. Created by Claude Code on 2026-10-13.
      */
    def sendNoContent: TaskHandler[Responded] =
      ZIO.serviceWithZIO[SH3Event](_.sendNoContent)

    /** Ends the response with no body and the given status. See [[SH3Event.sendNoContent]].
      * Created by Claude Code on 2026-10-13.
      */
    def sendNoContent(status: Int): TaskHandler[Responded] =
      ZIO.serviceWithZIO[SH3Event](_.sendNoContent(status))

    def `$fetch`[A: NativeConverter](url: String): TaskHandler[A] =
      ZIO.serviceWithZIO[SH3Event](_.`$fetch`[A](url))
end ZHandler

final case class ErrorResponse(errors: NonEmptyList[String]) extends NoStackTrace
    derives JsonEncoder

given [A: JsonEncoder]: JsonEncoder[NonEmptyList[A]] = JsonEncoder.list.contramap(_.toList)

trait SafeError extends NoStackTrace:
    val message: String
    val maybeUnderlying: Option[Throwable] = None

/** The failures a handler can raise. `ErrorResponse` is itself a `Throwable`, so matches must test
  * for it first.
  *
  * Created by Claude Code on 2026-10-13.
  */
private type HandlerFailure = ErrorResponse | ::[Throwable] | Throwable

/** The error body text for an interrupted handler. The message ZIO builds names fiber ids, which
  * change from run to run; this text does not. Created by Claude Code on 2026-10-13.
  */
private val interruptedMessage: String = "The handler was interrupted."

/** Reads the text that the error body carries for one failure.
  *
  * Created by Claude Code on 2026-10-13. A `SafeError` keeps its text in `message` and passes
  * nothing to the `Throwable` constructor, so `getMessage()` returns `null` for it. The body for a
  * `MissingRouterParamError` or a `GetRouterParamsError` therefore read `{"errors":[null]}`. A
  * `SafeError` now reports its `message`, the text that was designed to be shown. An
  * `ErrorResponse` reports its own messages. Any other throwable reports `getMessage()` when it
  * has one and its class name otherwise, so the list never holds `null`. This exposes no new
  * exception text: the `getMessage()` of an unsafe error reached the client before this change,
  * and the safe/unsafe error boundary is a separate follow-up.
  */
private def failureMessage(t: Throwable): String = t match
  case safe: SafeError    => safe.message
  case err: ErrorResponse => err.errors.toList.mkString("; ")
  case other              => Option(other.getMessage()).getOrElse(other.getClass.getName)

/** Turns a failed handler into what h3 receives.
  *
  * Created by Claude Code on 2026-10-13. `mkHandler` used three nested `catch` steps before. They
  * wrote an error body without checking whether the response had started, and their first step
  * matched `ErrorResponse` as a plain `Throwable`, which replaced the status that
  * `ZHandler.failWith` had set with 500 and its messages with `null`.
  *
  * When the response has not started, this returns the `ErrorResponse` body that h3 serializes:
  *   - An `ErrorResponse` keeps the status the handler set and its own messages.
  *   - A `Throwable` or a non-empty list of them sets status 500 and reports their messages as
  *     [[failureMessage]] reads them, so a `SafeError` reports its `message`. (Changed by Claude
  *     Code on 2026-10-13: this bullet.)
  *   - A defect reports its message the same way. An interruption reports a fixed text, because
  *     the message ZIO builds for it names fiber ids. The status is unchanged, as before. (Changed
  *     by Claude Code on 2026-10-13: this bullet.)
  *
  * When the response has started, for example because `sendStream` wrote its first chunk before
  * the source failed, no second body can follow. This logs the failure and returns `undefined`,
  * which h3 ignores for a handled event.
  */
private def errorResponse(cause: Cause[HandlerFailure]): URIO[SH3Event, js.Any] =
  ZHandler.getEvent.flatMap: event =>
      val failure = cause.failureOrCause
      // Changed by Claude Code on 2026-10-13: every entry comes from `failureMessage`, so a
      // `SafeError` reports its `message` and no entry is `null`. An interruption has a fixed text.
      val messages: NonEmptyList[String] = failure match
        case Left(err: ErrorResponse) => err.errors
        case Left(errs: ::[?])        =>
          val throwables = errs.asInstanceOf[::[Throwable]]
          NonEmptyList(failureMessage(throwables.head), throwables.tail.map(failureMessage)*)
        case Left(t: Throwable)       => NonEmptyList(failureMessage(t))
        case Right(defects)           =>
          NonEmptyList(defects.defects.headOption.fold(interruptedMessage)(failureMessage))

      if event.handled then
          // Changed by Claude Code on 2026-10-13: the line names the request's method and path.
          ZIOH3.Console
            .error(
              s"[zio-h3] ${event.method} ${event.path}: the handler failed after the response started." +
                " The response was ended without an error body:",
              messages.toList.mkString("; "),
            )
            .ignore
            .as(js.undefined.asInstanceOf[js.Any])
      else
          val body: js.Any = ErrorResponse(messages).toNative
          failure match
            case Left(_: ErrorResponse) => ZIO.succeed(body)
            case Left(_)                => event.setResponseStatus(500).orDie.as(body)
            case Right(_)               => ZIO.succeed(body)
      end if

/** Checks a successful handler result against the response state and returns what h3 receives.
  *
  * Created by Claude Code on 2026-10-13. The compiler makes sure that a handler finishes with a
  * [[Responded]] proof or with a body value. It cannot see whether `sendStream` ran before a body
  * was returned, or whether a proof is honest. This step closes those two gaps at run time:
  *   - The response already started and the value is a body: no second body can follow. This logs
  *     one `[zio-h3]` warning and returns `undefined`, which h3 ignores for a handled event. The
  *     client receives the response the handler already sent.
  *   - The response did not start and the value is `undefined`: nothing was written. A bare h3 app
  *     would answer 404 as if the route did not exist, and Nitro's preemptive router would answer an
  *     empty 204, both without a trace. This logs an error, sets status 500, and returns an
  *     `ErrorResponse` body that names the cause. If the connection is already closed, for example
  *     because the client left before the first chunk, nothing can be sent: this logs a warning and
  *     returns `undefined`.
  *   - Otherwise the value goes to h3 unchanged. `null` is a body here: h3 answers it with 204.
  */
private def deliver(native: js.Any): URIO[SH3Event, js.Any] =
  ZHandler.getEvent.flatMap: event =>
      val noBody = js.isUndefined(native)
      // Node sets `destroyed` on the response once its socket is gone; h3's `handled` does not read
      // it. Created by Claude Code on 2026-10-13.
      def connectionClosed: Boolean =
        event.node.res.asInstanceOf[js.Dynamic].destroyed.asInstanceOf[js.UndefOr[Boolean]].getOrElse(false)
      val route  = s"${event.method} ${event.path}"
      if event.handled && !noBody then
          ZIOH3.Console
            .warn(
              s"[zio-h3] $route: the handler returned a value after the response was sent. The value" +
                " was dropped. End the handler with the call that sent the response.",
            )
            .ignore
            .as(js.undefined.asInstanceOf[js.Any])
      else if !event.handled && noBody then
          val message = "The handler finished without sending a response."
          if connectionClosed then
              ZIOH3.Console
                .warn(s"[zio-h3] $route: $message The connection is closed, so nothing can be sent.")
                .ignore
                .as(js.undefined.asInstanceOf[js.Any])
          else
              ZIOH3.Console.error(s"[zio-h3] $route: $message Answering 500.").ignore *>
                event.setResponseStatus(500).orDie.as(ErrorResponse(NonEmptyList(message)).toNative)
      else ZIO.succeed(native)

/** Wraps a ZIO handler as an h3 event handler function.
  *
  * Changed by Claude Code on 2026-10-13: the result conversion goes through [[ResultConverter]].
  * A handler that finishes with a [[Responded]] proof resolves to JavaScript `undefined`, which h3
  * reads as "the response was written". Any other result converts through its `NativeConverter`.
  * A handler that finishes with `Unit` does not compile. Handler code imports nothing for this.
  * [[deliver]] then checks the converted value against the response state.
  */
def mkHandler[A: ResultConverter](
    runtime: Runtime[Any] = Runtime.default,
    h: Handler[ErrorResponse | ::[Throwable] | Throwable, A],
)(using ec: ExecutionContext): CommonEvHandler =
    // Changed by Claude Code on 2026-10-13: every failure, defect, and interruption goes through
    // `errorResponse`, which checks the response state before it writes anything; every success
    // goes through `deliver`, which does the same for the result.
    val handled: URIO[SH3Event, js.Any] =
      h.map(summon[ResultConverter[A]].toNative).flatMap(deliver).catchAllCause(errorResponse)

    js.Any
      .fromFunction1 { (event: H3Event1) =>
        Unsafe.unsafe { implicit unsafe =>
          runtime.unsafe
            .runToFuture(
              handled.provide(ZLayer.succeed(SH3Event(event))),
            )
            .toJSPromise
        }
      }
      .asInstanceOf[CommonEvHandler]
end mkHandler

/** Defines an h3 event handler from a ZIO handler with the default runtime. See [[mkHandler]] for
  * the result conversion and the error boundary.
  *
  * Changed by Claude Code on 2026-10-13: `A` needs a [[ResultConverter]], not a `NativeConverter`.
  */
def defineEventHandler[A: ResultConverter](
    h: Handler[ErrorResponse | ::[Throwable] | Throwable, A],
)(using ec: ExecutionContext): CommonEvHandler =
  defineEventHandler(Runtime.default, h)

// Changed by Claude Code on 2026-10-13: `A` needs a `ResultConverter`, not a `NativeConverter`.
def defineEventHandler[A: ResultConverter](
    runtime: Runtime[Any],
    h: Handler[ErrorResponse | ::[Throwable] | Throwable, A],
)(using ec: ExecutionContext): CommonEvHandler =
  h3DefineEventHandler[EventHandlerRequest, AnyEvHandlerRes](
    EventHandlerObject(mkHandler(runtime, h)),
  )

object ZIOH3:

    object Console:
        def log(msg: Any, additionalParams: Any*): Task[Unit] =
          ZIO.attempt(dom.console.log(msg, additionalParams*))

        def error(msg: Any, additionalParams: Any*): Task[Unit] =
          ZIO.attempt(dom.console.error(msg, additionalParams*))

        def warn(msg: Any, additionalParams: Any*): Task[Unit] =
          ZIO.attempt(dom.console.warn(msg, additionalParams*))

        def info(msg: Any, additionalParams: Any*): Task[Unit] =
          ZIO.attempt(dom.console.info(msg, additionalParams*))
    end Console
end ZIOH3
