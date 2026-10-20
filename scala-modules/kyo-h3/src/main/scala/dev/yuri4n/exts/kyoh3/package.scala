package dev.yuri4n.exts.kyoh3

import kyo.{Abort, Async, Env, Scope, Var, `<`}

// claude 2026-10-19: changed: one merged Env[Fetch & HandlerTransport]: the injected $fetch and the transport behind sendStream. Two separate Env entries break Env.run(service)(program(request)) in Kyo RC7 (Reducible)
// claude 2026-10-20: changed: the Scaladoc explains the merged Env (ADR-0006, Q9) and names KHandler.test
/** The effects every Kyo handler may use. The core handles all of them for each request:
  * `Async` and `Scope` (request-scoped resources), `Abort[Throwable]` (typed and safe errors),
  * `Var[ResponseHead]` (status, headers and cookies, written once after the handler finishes, or
  * when `Response.sendStream` is called), and `Env[Fetch & HandlerTransport]`: the request's
  * server-side `$fetch` and the native response that `Response.sendStream` writes. `Env.use[Fetch]`
  * works inside a handler, because Kyo's `Env` is covariant.
  *
  * The two per-request services share one `Env` entry on purpose. With two entries,
  * `Env[Fetch] & Env[HandlerTransport]`, the documented wiring
  * `KHandler.define(request => Env.run(service)(program(request)))` does not compile in Kyo
  * 1.0.0-RC7: after `Env.run` removes your service, Kyo's `Reducible` cannot reduce the two
  * remaining entries (the compiler reports `?1.SReduced`). One entry with an intersection type
  * compiles, so every future per-request service joins the same intersection. You never provide
  * it yourself: `KHandler.define` does for a request, and `KHandler.test` does in a test.
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
  ServerEvent, // claude 2026-10-20: changed: export ServerEvent (ADR-0007)
  SetCookie,
  Status,
  SuccessEnvelope,
  TestResponse, // claude 2026-10-20: changed: export TestResponse (ADR-0006)
  WireField,
  WireShape,
  WireType,
}
