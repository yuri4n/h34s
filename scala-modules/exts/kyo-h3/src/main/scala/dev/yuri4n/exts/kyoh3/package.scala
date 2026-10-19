package dev.yuri4n.exts.kyoh3

import kyo.{Abort, Async, Env, Scope, Var, `<`}

// claude 2026-10-19: changed: one merged Env[Fetch & HandlerTransport]: the injected $fetch and the transport behind sendStream. Two separate Env entries break Env.run(service)(program(request)) in Kyo RC7 (Reducible)
/** The effects every Kyo handler may use. The core handles all of them for each request:
  * `Async` and `Scope` (request-scoped resources), `Abort[Throwable]` (typed and safe errors),
  * `Var[ResponseHead]` (status, headers and cookies, written once after the handler finishes), and
  * `Env[Fetch & HandlerTransport]`: the request's server-side `$fetch` and the native response that
  * `Response.sendStream` writes. `Env.use[Fetch]` works inside a handler, because Kyo's `Env` is
  * covariant.
  */
type HandlerEffects = Async & Scope & Abort[Throwable] & Var[ResponseHead] & Env[Fetch & HandlerTransport]

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
  Sent, // claude 2026-10-19: changed: export Sent
  SetCookie,
  Status,
  SuccessEnvelope,
}
