package dev.yuri4n.exts.kyoh3

import kyo.{Abort, Async, Scope, Var, `<`}

/** The effects every Kyo handler may use. The core handles all of them for each request:
  * `Async` and `Scope` (request-scoped resources), `Abort[Throwable]` (typed and safe errors) and
  * `Var[ResponseHead]` (status, headers and cookies, written once after the handler finishes).
  */
type HandlerEffects = Async & Scope & Abort[Throwable] & Var[ResponseHead]

type TaskHandler[A] = A < HandlerEffects

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
