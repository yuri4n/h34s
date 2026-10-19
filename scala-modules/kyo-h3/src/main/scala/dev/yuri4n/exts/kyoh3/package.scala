package dev.yuri4n.exts.kyoh3

import kyo.{Abort, Async, Env, Scope, Var, `<`}

// claude 2026-10-19: changed: Env[HandlerTransport], the transport behind sendStream
/** The effects every Kyo handler may use. The core handles all of them for each request:
  * `Async` and `Scope` (request-scoped resources), `Abort[Throwable]` (typed and safe errors),
  * `Var[ResponseHead]` (status, headers and cookies, written once after the handler finishes), and
  * `Env[HandlerTransport]`: the native response that `Response.sendStream` writes.
  */
type HandlerEffects = Async & Scope & Abort[Throwable] & Var[ResponseHead] & Env[HandlerTransport]

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
  Method,
  OnFailure,
  Request,
  RequestBody,
  ResponseHead,
  Route,
  SafeError,
  SameSite,
  Secret,
  Sent, // claude 2026-10-19: changed: export Sent
  SetCookie,
  Status,
  SuccessEnvelope,
  WireField,
  WireShape,
  WireType,
}
