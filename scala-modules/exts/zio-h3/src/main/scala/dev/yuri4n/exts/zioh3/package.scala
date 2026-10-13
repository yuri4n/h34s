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

    def respondWith(response: Response): Task[Unit] =
      respondWithZIO(ZIO.succeed(response))

    def respondWithZIO[E](
        response: IO[E, Response],
    )(using toT: E <:< Throwable): IO[E | Throwable, Unit] =
      response.toPromiseJS.flatMap: promise =>
          ZIO.fromFuture: //
              implicit ec =>
                  event
                    .respondWith(promise.asInstanceOf[PromiseLike[Response]])
                    .toFuture

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
      *     then succeeds with `Unit`, because the response is over for everyone.
      *
      * Remaining limits of this transport:
      *   - Each native chunk reaches Node's `response.write` unchanged, so it must be a string, a
      *     `Buffer`, or a `Uint8Array`. The compiler does not check this.
      *   - h3 sets no `Content-Type` for a streamed body. The toolbox has no header helper yet;
      *     set it through `event.node.res` before this call if clients need one.
      *   - The handler must finish with `Unit` after this call. Keep the `NativeConverter[Unit]`
      *     from `dev.yuri4n.exts.ntcvzio` in scope; it converts `()` to JavaScript `undefined`.
      *     h3 treats any other success value as a second response body and fails with
      *     `ERR_HTTP_HEADERS_SENT`. A failure after this call is safe: `mkHandler` logs it and
      *     writes nothing.
      *   - Node sends the headers with the first chunk, even an empty string. From then on the
      *     response has started and the late-failure behavior above applies.
      *   - The source runs eagerly. Web Streams backpressure does not reach it, and converted
      *     chunks wait in the stream's queue until Node writes them.
      *   - Nitro's in-process fetch, which SSR `useFetch` and `$fetch` use, does not pipe the
      *     stream. h3 hands it to `new Response(...)`, where only `Uint8Array` chunks are readable.
      *     That path has no Node socket, so the disconnect handling above does not apply to it.
      */
    def sendStream[A: NativeConverter](
        stream: TaskStream[A],
    ): Task[Unit] =
      for
          runtime                <- ZIO.runtime
          given ExecutionContext <- ZIO.executor.map(_.asExecutionContext)
          clientClosed           <- Promise.make[Nothing, Unit]
          _                      <- ZIO.attempt(onResponseClose(() => completeFromJS(runtime, clientClosed)))
          readable                = stream.interruptWhen(clientClosed.await).toReadableStream(runtime)
          _                      <- ZIO
                                      .fromPromiseJS(h3SendStream(event, readable))
                                      .tapError(_ => terminateStartedResponse)
      yield ()

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

    def `$fetch`[A: NativeConverter](url: String): Task[A] =
      event.asInstanceOf[js.Dynamic].`$fetch`(url.asInstanceOf[js.Any]).fromNative_[A]

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
      * and disconnect behavior.
      */
    def sendStream[A: NativeConverter](stream: TaskStream[A]): TaskHandler[Unit] =
      ZIO.serviceWithZIO[SH3Event](_.sendStream(stream))

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

/** Turns a failed handler into what h3 receives.
  *
  * Created by Claude Code on 2026-10-13. `mkHandler` used three nested `catch` steps before. They
  * wrote an error body without checking whether the response had started, and their first step
  * matched `ErrorResponse` as a plain `Throwable`, which replaced the status that
  * `ZHandler.failWith` had set with 500 and its messages with `null`.
  *
  * When the response has not started, this returns the `ErrorResponse` body that h3 serializes:
  *   - An `ErrorResponse` keeps the status the handler set and its own messages.
  *   - A `Throwable` or a non-empty list of them sets status 500 and reports their messages.
  *   - A defect or an interruption reports its message. The status is unchanged, as before.
  *
  * When the response has started, for example because `sendStream` wrote its first chunk before
  * the source failed, no second body can follow. This logs the failure and returns `undefined`,
  * which h3 ignores for a handled event.
  */
private def errorResponse(cause: Cause[HandlerFailure]): URIO[SH3Event, js.Any] =
  ZHandler.getEvent.flatMap: event =>
      val failure = cause.failureOrCause
      val messages: NonEmptyList[String] = failure match
        case Left(err: ErrorResponse) => err.errors
        case Left(errs: ::[?])        =>
          val throwables = errs.asInstanceOf[::[Throwable]]
          NonEmptyList(throwables.head.getMessage(), throwables.tail.map(_.getMessage())*)
        case Left(t: Throwable)       => NonEmptyList(t.getMessage())
        case Right(defect)            => NonEmptyList(defect.squash.getMessage())

      if event.handled then
          ZIOH3.Console
            .error(
              "[zio-h3] The handler failed after the response started. The response was ended" +
                " without an error body:",
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

def mkHandler[A: NativeConverter](
    runtime: Runtime[Any] = Runtime.default,
    h: Handler[ErrorResponse | ::[Throwable] | Throwable, A],
)(using ec: ExecutionContext): CommonEvHandler =
    // Changed by Claude Code on 2026-10-13: every failure, defect, and interruption goes through
    // `errorResponse`, which checks the response state before it writes anything.
    val handled: URIO[SH3Event, js.Any] = h.map(_.toNative).catchAllCause(errorResponse)

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

def defineEventHandler[A: NativeConverter](
    h: Handler[ErrorResponse | ::[Throwable] | Throwable, A],
)(using ec: ExecutionContext): CommonEvHandler =
  defineEventHandler(Runtime.default, h)

def defineEventHandler[A: NativeConverter](
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
