package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.h3.internal.HeaderRules
import kyo.{Frame, Var, `<`}

/** Response head operations. They change local state only; the core writes the head once, after
  * the handler finishes. `Abort.recover` does not roll these changes back.
  *
  * Invalid header names, header values, cookie names or redirect statuses are programming errors.
  * The check runs when the operation runs, not when you build the value: the handler then fails
  * with an `IllegalArgumentException`, which the core reports as a defect (opaque 500).
  */
object Response:
    def status(status: Status)(using Frame): Unit < Var[ResponseHead] =
      Var.updateDiscard[ResponseHead](_.withStatus(status))

    /** Sets a header, replacing earlier values. */
    def header(name: String, value: String, onFailure: OnFailure = OnFailure.Discard)(using Frame): Unit < Var[ResponseHead] =
      checked(HeaderRules.requireHeader(name, value))(_.withHeader(name, value, onFailure))

    /** Adds a header value, keeping earlier values. */
    def appendHeader(name: String, value: String, onFailure: OnFailure = OnFailure.Discard)(using
        Frame,
    ): Unit < Var[ResponseHead] =
      checked(HeaderRules.requireHeader(name, value))(_.appendHeader(name, value, onFailure))

    def cookie(cookie: SetCookie, onFailure: OnFailure = OnFailure.Discard)(using Frame): Unit < Var[ResponseHead] =
      checked(HeaderRules.requireCookieName(cookie.name))(_.withCookie(cookie, onFailure))

    /** Expires a cookie. `path` and `domain` must match the cookie that was set; `secure` sends the
      * `Secure` attribute, which browsers require to delete a `__Secure-` or `__Host-` cookie.
      */
    def deleteCookie(
        name: String,
        path: Option[String] = Some("/"),
        domain: Option[String] = None,
        secure: Boolean = true,
        onFailure: OnFailure = OnFailure.Discard,
    )(using Frame): Unit < Var[ResponseHead] =
      checked(HeaderRules.requireCookieName(name))(_.withoutCookie(name, path, domain, secure, onFailure))

    /** Sets a 3xx status and the `Location` header. */
    def redirect(location: String, status: Status = Status.Found)(using Frame): Unit < Var[ResponseHead] =
      checked {
        require(status.code >= 300 && status.code <= 399, s"Redirect status must be 3xx, got ${status.code}")
        HeaderRules.requireHeader("location", location)
      }(_.redirect(location, status))

    /** The head that the handler has set so far (defaults passed to `define` are not included). */
    def head(using Frame): ResponseHead < Var[ResponseHead] = Var.get[ResponseHead]

    /** Runs `check` inside the program's continuation, where the core's `Abort.run` sees a throw as a
      * defect. (The function given to `Var.update` runs in the Var handler, outside that boundary.)
      */
    private inline def checked(inline check: => Unit)(inline change: ResponseHead => ResponseHead)(using
        Frame,
    ): Unit < Var[ResponseHead] =
      Var.use[ResponseHead] { head =>
        check
        Var.setDiscard(change(head))
      }
end Response
