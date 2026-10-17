package dev.yuri4n.exts.h3

import org.getshaka.nativeconverter.{NativeConverter, ParseState}

import scala.scalajs.js
import scala.util.control.NoStackTrace

/** One public error entry: an approved message and, when known, the input path it is about.
  *
  * The native shape is `{ "message": string, "path"?: string }`; `path` is omitted when `None`.
  * Neither field may contain input values.
  */
final case class ErrorEntry(message: String, path: Option[String] = None)

object ErrorEntry:
    given NativeConverter[ErrorEntry]:
        extension (entry: ErrorEntry)
            def toNative: js.Any =
              val native = js.Dynamic.literal(message = entry.message)
              entry.path.foreach(path => native.path = path)
              native

        // native-converter 0.9 cannot report an `undefined` value (its message would call
        // JSON.stringify(undefined)), so failures are reported on a defined value.
        def fromNative(state: ParseState): ErrorEntry =
          val json = state.json
          if js.isUndefined(json) then ParseState(null).fail("an error entry object")
          else if json == null || js.typeOf(json) != "object" then state.fail("an error entry object")
          else
              val native  = json.asInstanceOf[js.Dynamic]
              val message = native.message.asInstanceOf[js.Any]
              val path    = native.path.asInstanceOf[js.Any]
              if js.typeOf(message) != "string" then state.fail("an error entry with a string message")
              else if js.isUndefined(path) || path == null then ErrorEntry(message.asInstanceOf[String])
              else if js.typeOf(path) != "string" then state.atKey("path", path).fail("a string")
              else ErrorEntry(message.asInstanceOf[String], Some(path.asInstanceOf[String]))
end ErrorEntry

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

    /** The public entries of the error body. One entry with `message` unless overridden. */
    def errors: List[ErrorEntry] = List(ErrorEntry(message))

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
  * `entries` must contain only application-authored messages and paths, never input values.
  * Without entries, the response has one generic entry that says only that the input does not
  * match the expected shape.
  */
final case class InvalidInput(entries: List[ErrorEntry] = Nil) extends SafeError:
    override def status: Int = 422

    override def errors: List[ErrorEntry] =
      if entries.isEmpty then List(ErrorEntry(InvalidInput.genericMessage)) else entries

    /** The entries as one line, for logs: `path: message` joined with "; ". */
    def message: String =
      errors.map(entry => entry.path.fold(entry.message)(path => s"$path: ${entry.message}")).mkString("; ")

object InvalidInput:
    val genericMessage: String = "Request body does not match the expected shape"

/** The server is misconfigured (for example, missing or invalid runtime config).
  *
  * It is never shown to clients: the response is an opaque 500. The message must not contain
  * configuration values.
  */
final case class ConfigError(message: String) extends RuntimeException(message) with NoStackTrace
