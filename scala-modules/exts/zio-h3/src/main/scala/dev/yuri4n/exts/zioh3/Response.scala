package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.h3.internal.HeaderRules
import zio.{URIO, ZIO, ZState}

/** Response head operations over ZIO's `ZState[ResponseHead]`. They change local state only; h34s
  * writes the head once, after the handler finishes. A failed handler keeps only the entries
  * marked `OnFailure.Keep` and takes its status from the error.
  *
  * `ZState` is backed by a `FiberRef`: a forked fiber works on its own copy, and joining it copies
  * the child's head back into the parent (ZIO's default `FiberRef` join). `catchAll` and other
  * recoveries do not roll changes back.
  *
  * Invalid header names, header values, cookie names or redirect statuses are programming errors:
  * the effect dies, which h34s reports as an opaque 500.
  */
object Response:
    def status(status: Status): URIO[ZState[ResponseHead], Unit] =
      ZIO.updateState[ResponseHead](_.withStatus(status))

    /** Sets a header, replacing earlier values. */
    def header(name: String, value: String, onFailure: OnFailure = OnFailure.Discard): URIO[ZState[ResponseHead], Unit] =
      ZIO.suspendSucceed {
        HeaderRules.requireHeader(name, value)
        ZIO.updateState[ResponseHead](_.withHeader(name, value, onFailure))
      }

    /** Adds a header value, keeping earlier values. */
    def appendHeader(name: String, value: String, onFailure: OnFailure = OnFailure.Discard)
        : URIO[ZState[ResponseHead], Unit] =
      ZIO.suspendSucceed {
        HeaderRules.requireHeader(name, value)
        ZIO.updateState[ResponseHead](_.appendHeader(name, value, onFailure))
      }

    def cookie(cookie: SetCookie, onFailure: OnFailure = OnFailure.Discard): URIO[ZState[ResponseHead], Unit] =
      ZIO.suspendSucceed {
        HeaderRules.requireCookieName(cookie.name)
        ZIO.updateState[ResponseHead](_.withCookie(cookie, onFailure))
      }

    def deleteCookie(name: String, path: Option[String] = Some("/")): URIO[ZState[ResponseHead], Unit] =
      ZIO.suspendSucceed {
        HeaderRules.requireCookieName(name)
        ZIO.updateState[ResponseHead](_.withoutCookie(name, path))
      }

    /** Sets a 3xx status and the `Location` header. */
    def redirect(location: String, status: Status = Status.Found): URIO[ZState[ResponseHead], Unit] =
      ZIO.suspendSucceed {
        require(status.code >= 300 && status.code <= 399, s"Redirect status must be 3xx, got ${status.code}")
        HeaderRules.requireHeader("location", location)
        ZIO.updateState[ResponseHead](_.redirect(location, status))
      }

    /** The head that the handler has set so far (defaults from `HandlerOptions` are not included). */
    def head: URIO[ZState[ResponseHead], ResponseHead] = ZIO.getState[ResponseHead]
end Response
