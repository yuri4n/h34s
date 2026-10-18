package dev.yuri4n.exts.zioh3

import zio.json.JsonEncoder
import zio.prelude.NonEmptyList
import zio.stream.{Stream, ZStream}
import zio.{Scope, ZIO, ZState}

import scala.util.control.NoStackTrace

/** What h34s supplies to every ZIO handler: the response head as `ZState` (status, headers and
  * cookies, written once after the handler finishes) and the request `Scope` (resources that live
  * until the response is complete, also while a streamed body is sent).
  */
type HandlerEnv = ZState[ResponseHead] & Scope

type ZHandler[-R, +E, +A] = ZIO[HandlerEnv & R, E, A]
type Handler[+E, +A]      = ZHandler[Any, E, A]
type TaskHandler[+A]      = Handler[Throwable, A]
type URHandler[-R, +A]    = ZHandler[R, Nothing, A]
type UHandler[+A]         = ZHandler[Any, Nothing, A]
type RHandler[-R, +A]     = ZHandler[R, Throwable, A]

type TaskStream[+A]   = Stream[Throwable, A]
type RStream[-R, +A]  = ZStream[R, Throwable, A]
type URStream[-R, +A] = ZStream[R, Nothing, A]

export dev.yuri4n.exts.h3.{
  BodyLimit,
  ConfigError,
  CookieEntry,
  DeleteCookie,
  ErrorEntry,
  ErrorEnvelope,
  EventHandler,
  HandlerOptions,
  HeaderEntry,
  HeaderMode,
  HttpError,
  InvalidInput,
  OnFailure,
  Request,
  RequestBody,
  ResponseHead,
  SafeError,
  SameSite,
  Secret,
  SetCookie,
  Status,
  SuccessEnvelope,
}

/** Approved error messages with a status, as `ZHandler.failWith` builds them.
  *
  * Fail with it through the typed error channel. Each message becomes one error entry without a
  * path. A status outside 400-599 is a defect (opaque 500), like any unapproved `SafeError`.
  */
final case class ErrorResponse(errors: NonEmptyList[String], status: Int = 400) extends NoStackTrace
    derives JsonEncoder:
    override def getMessage: String = errors.toList.mkString("; ")

given [A: JsonEncoder] => JsonEncoder[NonEmptyList[A]] = JsonEncoder.list[A].contramap(_.toList)
