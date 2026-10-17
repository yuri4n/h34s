package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.h3.internal.HeaderRules
import kyo.{Frame, Var, `<`}

/** Response head operations. They change local state only; the core writes the head once, after
  * the handler finishes. `Abort.recover` does not roll these changes back.
  *
  * Invalid header names, header values, cookie names or redirect statuses are programming errors:
  * the call throws `IllegalArgumentException`, which the core reports as a defect.
  */
object Response:
    def status(status: Status)(using Frame): Unit < Var[ResponseHead] =
      Var.updateDiscard[ResponseHead](_.withStatus(status))

    /** Sets a header, replacing earlier values. */
    def header(name: String, value: String, onFailure: OnFailure = OnFailure.Discard)(using Frame): Unit < Var[ResponseHead] =
      HeaderRules.requireHeader(name, value)
      Var.updateDiscard[ResponseHead](_.withHeader(name, value, onFailure))

    /** Adds a header value, keeping earlier values. */
    def appendHeader(name: String, value: String, onFailure: OnFailure = OnFailure.Discard)(using
        Frame,
    ): Unit < Var[ResponseHead] =
      HeaderRules.requireHeader(name, value)
      Var.updateDiscard[ResponseHead](_.appendHeader(name, value, onFailure))

    def cookie(cookie: SetCookie, onFailure: OnFailure = OnFailure.Discard)(using Frame): Unit < Var[ResponseHead] =
      HeaderRules.requireCookieName(cookie.name)
      Var.updateDiscard[ResponseHead](_.withCookie(cookie, onFailure))

    def deleteCookie(name: String, path: Option[String] = Some("/"))(using Frame): Unit < Var[ResponseHead] =
      HeaderRules.requireCookieName(name)
      Var.updateDiscard[ResponseHead](_.withoutCookie(name, path))

    /** Sets a 3xx status and the `Location` header. */
    def redirect(location: String, status: Status = Status.Found)(using Frame): Unit < Var[ResponseHead] =
      require(status.code >= 300 && status.code <= 399, s"Redirect status must be 3xx, got ${status.code}")
      HeaderRules.requireHeader("location", location)
      Var.updateDiscard[ResponseHead](_.redirect(location, status))

    /** The head that the handler has set so far (defaults passed to `define` are not included). */
    def head(using Frame): ResponseHead < Var[ResponseHead] = Var.get[ResponseHead]
end Response
