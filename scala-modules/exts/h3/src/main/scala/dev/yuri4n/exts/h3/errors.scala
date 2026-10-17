package dev.yuri4n.exts.h3

import scala.util.control.NoStackTrace

/** An error whose status, message and headers are approved for the HTTP response.
  *
  * Fail with it through the typed error channel (Kyo `Abort.fail`, ZIO `ZIO.fail`). A `SafeError`
  * that is thrown, or that escapes from a decoder or converter, is a defect: h34s answers with an
  * opaque 500 instead. Domain errors that the client may see extend this trait.
  */
trait SafeError extends RuntimeException with NoStackTrace:
    def message: String

    /** A client (4xx) or server (5xx) status. Any other value is treated as a defect (500). */
    def status: Int = 400

    /** Extra response headers for this error, for example `WWW-Authenticate` on a 401. */
    def headers: List[(String, String)] = Nil

    override def getMessage: String = message

/** A safe error with an explicit status, message and optional headers. */
final case class HttpError(
    override val status: Int,
    message: String,
    override val headers: List[(String, String)] = Nil,
) extends SafeError:
    /** Adds a response header to this error. */
    def header(name: String, value: String): HttpError = copy(headers = headers :+ (name -> value))

object HttpError:
    def badRequest(message: String): HttpError    = HttpError(400, message)
    def unauthorized(message: String): HttpError  = HttpError(401, message)
    def notFound(message: String): HttpError      = HttpError(404, message)
    def conflict(message: String): HttpError      = HttpError(409, message)
    def unprocessable(message: String): HttpError = HttpError(422, message)

/** Client input did not match the expected shape or schema (status 422).
  *
  * `details` must contain only application-authored messages, never input values. Without
  * details, the response says only that the input does not match the expected shape.
  */
final case class InvalidInput(details: List[String] = Nil) extends SafeError:
    def message: String =
      if details.isEmpty then InvalidInput.genericMessage else details.mkString("; ")
    override def status: Int = 422

object InvalidInput:
    val genericMessage: String = "Request body does not match the expected shape"

/** The server is misconfigured (for example, missing or invalid runtime config).
  *
  * It is never shown to clients: the response is an opaque 500. The message must not contain
  * configuration values.
  */
final case class ConfigError(message: String) extends RuntimeException(message) with NoStackTrace
