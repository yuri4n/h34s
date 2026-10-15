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
import zio.{Cause, IO, Promise, Runtime, Scope, Task, URIO, Unsafe, ZIO, ZLayer}

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

    // Set by the server integration, never from request headers or query parameters.
    private[zioh3] def developmentMode: Boolean =
      val config = event.context.asInstanceOf[js.Dynamic].h34s
      if config == null || js.isUndefined(config) then false
      else
        val value = config.development
        js.typeOf(value) == "boolean" && value.asInstanceOf[Boolean]

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

    /** Explicit parsing policy; the no-argument reader retains h3's defaults. */
    def readBody[T: NativeConverter](strict: Boolean): Task[T] =
      ZIO
        .fromFuture(implicit ec => h3ReadBody[T, H3Event1, js.Any](event, typings.h3.anon.Strict().setStrict(strict)).toFuture)
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

    def sendStream[A: NativeConverter](
        stream: TaskStream[A],
    ): Task[Unit] =
      ZIO.scoped:
        for
          runtime                <- ZIO.runtime
          given ExecutionContext <- ZIO.executor.map(_.asExecutionContext)
          disconnected           <- Promise.make[Nothing, Unit]
          response = event.node.res.asInstanceOf[js.Dynamic]
          _ <- ZIO.fail(new UnsupportedOperationException("Streaming requires a Node HTTP response"))
            .unless(response.socket != null && !js.isUndefined(response.socket))
          onClose = (() => Unsafe.unsafe { implicit unsafe =>
            runtime.unsafe.run(disconnected.succeed(())).getOrThrowFiberFailure()
            ()
          }): js.Function0[Unit]
          _ <- ZIO.acquireRelease(ZIO.attempt {
            response.once("close", onClose)
            if response.destroyed.asInstanceOf[Boolean] || response.closed.asInstanceOf[Boolean] then onClose()
            ()
          })(
            _ => ZIO.attempt { response.removeListener("close", onClose); () }.orDie,
          )
          _ <- ZIO.uninterruptibleMask { restore =>
            ZIO.attempt(h3SendStream(event, stream.interruptWhen(disconnected.await).toReadableStream(runtime))).flatMap { sending =>
              restore(ZIO.fromPromiseJS(sending))
                .onInterrupt(disconnected.succeed(()) *> ZIO.fromPromiseJS(sending).ignore)
            }
          }
            .onError(_ => ZIO.attempt { if response.headersSent.asInstanceOf[Boolean] then response.destroy(); () }.orDie)
        yield ()

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

    /** Define a request-scoped ZIO handler. Supply application services with ordinary ZIO provisioning. */
    def define[A: NativeConverter](
        program: ZIO[SH3Event & Scope, ErrorResponse | ::[Throwable] | Throwable, A],
    ): CommonEvHandler =
      given ExecutionContext = ExecutionContext.global
      defineEventHandler(ZIO.scoped[SH3Event](program))

    /** Side-effecting handlers, including sendStream, return native JavaScript undefined. */
    def define(
        program: ZIO[SH3Event & Scope, ErrorResponse | ::[Throwable] | Throwable, Unit],
    ): CommonEvHandler = define[Unit](program)

    def readBody[A: NativeConverter]: TaskHandler[A] =
      ZIO.serviceWithZIO[SH3Event](_.readBody[A])

    def readBody[A: NativeConverter](strict: Boolean): TaskHandler[A] =
      ZIO.serviceWithZIO[SH3Event](_.readBody[A](strict))

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
        override def status: Int                         = 500
        override val maybeUnderlying: Option[Throwable] = Some(underlying)

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
    def status: Int = 400
    val maybeUnderlying: Option[Throwable] = None

def mkHandler[A: NativeConverter](
    runtime: Runtime[Any] = Runtime.default,
    h: Handler[ErrorResponse | ::[Throwable] | Throwable, A],
)(using ec: ExecutionContext): CommonEvHandler =
    val internalError = ErrorResponse(NonEmptyList("Internal server error"))

    def unexpected(cause: Cause[Any]): URIO[SH3Event, js.Any] =
      ZIO.logErrorCause("Unhandled handler failure", cause) *>
        ZHandler.setResponseStatus(500).orDie *>
        ZIO.serviceWith[SH3Event] { event =>
          val response = internalError.toNative
          if event.developmentMode then
            response.asInstanceOf[js.Dynamic].updateDynamic("debug")(
              js.Dynamic.literal(cause = cause.prettyPrint),
            )
          response
        }

    val handled =
      h.map(_.toNative)
        .catchAllCause { cause =>
          if cause.isInterruptedOnly then ZIO.refailCause(cause.stripFailures)
          else if cause.defects.nonEmpty then unexpected(cause)
          else cause.failureOption match
              case Some(err: ErrorResponse) => ZIO.succeed(err.toNative)
              case Some(err: SafeError) =>
                ZHandler.setResponseStatus(err.status).orDie.as(ErrorResponse(NonEmptyList(err.message)).toNative)
              case _ => unexpected(cause)
        }
    end handled

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
