package dev.yuri4n.exts.h3

/** An HTTP status code between 100 and 599. */
opaque type Status = Int

object Status:
    /** Validates a status code. */
    def apply(code: Int): Either[String, Status] =
      if code >= 100 && code <= 599 then Right(code)
      else Left(s"HTTP status must be between 100 and 599, got $code")

    /** For literal, known-valid codes. Throws `IllegalArgumentException` for any other value. */
    def unsafe(code: Int): Status =
      apply(code).fold(message => throw IllegalArgumentException(message), identity)

    extension (status: Status) def code: Int = status

    val Ok: Status                  = 200
    val Created: Status             = 201
    val Accepted: Status            = 202
    val NoContent: Status           = 204
    val MovedPermanently: Status    = 301
    val Found: Status               = 302
    val SeeOther: Status            = 303
    val NotModified: Status         = 304
    val TemporaryRedirect: Status   = 307
    val PermanentRedirect: Status   = 308
    val BadRequest: Status          = 400
    val Unauthorized: Status        = 401
    val Forbidden: Status           = 403
    val NotFound: Status            = 404
    val Conflict: Status            = 409
    val UnprocessableEntity: Status = 422
    val InternalServerError: Status = 500
end Status

/** Whether a response entry survives a failed handler. */
enum OnFailure:
    /** Applied only when the handler succeeds (the default). */
    case Discard

    /** Applied on success and on failure. */
    case Keep

/** `Set` replaces earlier values of the header; `Append` adds another value. */
enum HeaderMode:
    case Set, Append

final case class HeaderEntry(name: String, value: String, mode: HeaderMode, onFailure: OnFailure)

enum SameSite:
    case Strict, Lax, None

/** A `Set-Cookie` instruction. The defaults are the safe choice for session-like cookies. */
final case class SetCookie(
    name: String,
    value: String,
    path: Option[String] = Some("/"),
    maxAge: Option[Long] = scala.None, // seconds
    httpOnly: Boolean = true,
    secure: Boolean = true,
    sameSite: SameSite = SameSite.Lax,
    domain: Option[String] = scala.None,
)

/** Expires a cookie in the client. `path` and `domain` must match the cookie that was set.
  *
  * `secure` sends the `Secure` attribute, like `SetCookie`'s default. Browsers ignore a deletion of
  * a `__Secure-` or `__Host-` cookie without it.
  */
final case class DeleteCookie(
    name: String,
    path: Option[String] = Some("/"),
    domain: Option[String] = scala.None,
    secure: Boolean = true,
)

final case class CookieEntry(cookie: SetCookie | DeleteCookie, onFailure: OnFailure)

/** Response metadata as plain data: status, headers and cookies.
  *
  * The handler builds it as local state (Kyo `Var`, ZIO `ZState`); h34s writes it to the native
  * response once, after the handler and its finalizers finish. The status is last-write-wins;
  * headers and cookies are ordered entries.
  */
final case class ResponseHead(
    status: Option[Status],
    headers: Vector[HeaderEntry],
    cookies: Vector[CookieEntry],
):
    def withStatus(status: Status): ResponseHead = copy(status = Some(status))

    def withHeader(name: String, value: String, onFailure: OnFailure = OnFailure.Discard): ResponseHead =
      copy(headers = headers :+ HeaderEntry(name, value, HeaderMode.Set, onFailure))

    def appendHeader(name: String, value: String, onFailure: OnFailure = OnFailure.Discard): ResponseHead =
      copy(headers = headers :+ HeaderEntry(name, value, HeaderMode.Append, onFailure))

    def withCookie(cookie: SetCookie, onFailure: OnFailure = OnFailure.Discard): ResponseHead =
      copy(cookies = cookies :+ CookieEntry(cookie, onFailure))

    def withoutCookie(
        name: String,
        path: Option[String] = Some("/"),
        domain: Option[String] = scala.None,
        secure: Boolean = true,
        onFailure: OnFailure = OnFailure.Discard,
    ): ResponseHead =
      copy(cookies = cookies :+ CookieEntry(DeleteCookie(name, path, domain, secure), onFailure))

    /** Sets the status and the `Location` header. */
    def redirect(location: String, status: Status = Status.Found): ResponseHead =
      withStatus(status).withHeader("location", location)

    /** The entries that survive a failure. The status always comes from the error. */
    def onFailure: ResponseHead =
      ResponseHead(
        scala.None,
        headers.filter(_.onFailure == OnFailure.Keep),
        cookies.filter(_.onFailure == OnFailure.Keep),
      )

    /** `other` is applied after this head: its status wins when set, its entries come later. */
    def ++(other: ResponseHead): ResponseHead =
      ResponseHead(other.status.orElse(status), headers ++ other.headers, cookies ++ other.cookies)
end ResponseHead

object ResponseHead:
    val empty: ResponseHead = ResponseHead(scala.None, Vector.empty, Vector.empty)

    /** `Cache-Control: no-store`, kept on failure. A typical `AppHandler` default. */
    val noStore: ResponseHead = empty.withHeader("cache-control", "no-store", OnFailure.Keep)

    /** The head for a successful handler: the defaults, then everything the program set. */
    def forSuccess(defaults: ResponseHead, program: ResponseHead): ResponseHead = defaults ++ program

    /** The head for a failed handler: the error's status, the defaults, the entries the program
      * marked `OnFailure.Keep`, then the error's own headers. Everything else is discarded.
      */
    def forFailure(
        defaults: ResponseHead,
        program: ResponseHead,
        status: Status,
        errorHeaders: List[(String, String)],
    ): ResponseHead =
      val withError = errorHeaders.foldLeft(defaults ++ program.onFailure) { case (head, (name, value)) =>
        head.withHeader(name, value)
      }
      withError.withStatus(status)
end ResponseHead
