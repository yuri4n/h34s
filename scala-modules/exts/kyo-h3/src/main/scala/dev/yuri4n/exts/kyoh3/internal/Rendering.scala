package dev.yuri4n.exts.kyoh3.internal

import dev.yuri4n.exts.h3.internal.{H3, H3Event}
import dev.yuri4n.exts.h3.{
  DeleteCookie,
  ErrorEntry,
  ErrorEnvelope,
  HeaderMode,
  ResponseHead,
  SafeError,
  SameSite,
  SetCookie,
  Status,
  SuccessEnvelope,
}
import org.getshaka.nativeconverter.NativeConverter

import java.io.{PrintWriter, StringWriter}
import scala.scalajs.js
import scala.util.control.NonFatal

/** The wire format (HandlerOptions envelopes) and the single write of the response head. */
private[exts] object Rendering:
    /** A safe error approved for the client: a 4xx or 5xx status and at least one entry, each with
      * a non-null message and path. `errors` is application code, so a throw makes it unapproved.
      */
    def approved(error: SafeError): Option[(Status, List[ErrorEntry])] =
      try
          for
            status  <- Status(error.status).toOption.filter(_.code >= 400)
            entries <- Option(error.errors).filter(entries => entries.nonEmpty && entries.forall(validEntry))
          yield status -> entries
      catch case NonFatal(_) => None

    private def validEntry(entry: ErrorEntry): Boolean =
      entry != null && entry.message != null && entry.path != null && !entry.path.contains(null)

    /** A value body in its success envelope, and the content type to use when the handler set none.
      *
      * `Raw` never hands a converted value to h3 as is: h3 would send a string as `text/html` and
      * treat an object with a `buffer`, `pipe` or `arrayBuffer` field as a binary body. A string is
      * sent as `text/plain; charset=utf-8`; any other value as JSON text with `application/json`.
      * A value converting to `undefined` or `null` is sent as `null`, so h3 answers 204 in a plain app
      * and in Nitro alike.
      */
    def successBody(envelope: SuccessEnvelope, native: js.Any): (js.Any, Option[String]) = envelope match
        case SuccessEnvelope.Data => (js.Dynamic.literal(data = native), None)
        case SuccessEnvelope.Raw  =>
          if js.isUndefined(native) || native == null then (noContent, None)
          else if js.typeOf(native) == "string" then (native, Some(textContentType))
          else (js.JSON.stringify(native), Some(jsonContentType))

    val textContentType: String = "text/plain; charset=utf-8"
    val jsonContentType: String = "application/json"

    /** Adds `content-type` (and `x-content-type-options: nosniff`) when neither the head nor the
      * native response (for example a Nitro middleware) has a content type.
      */
    def withDefaultContentType(event: H3Event, head: ResponseHead, contentType: String): ResponseHead =
      def inHead(name: String) = head.headers.exists(_.name.equalsIgnoreCase(name))
      val response             = event.node.res.asInstanceOf[js.Dynamic]
      def onResponse(name: String) =
        js.typeOf(response.getHeader) == "function" && {
          val value = response.getHeader(name)
          !js.isUndefined(value) && value != null
        }
      if inHead("content-type") || onResponse("content-type") then head
      else
          val typed = head.withHeader("content-type", contentType)
          if inHead("x-content-type-options") || onResponse("x-content-type-options") then typed
          else typed.withHeader("x-content-type-options", "nosniff")

    /** h3 1.15 sends `null` with `sendNoContent`: 204 (or the status already set), no body. It
      * sends `undefined` only through a preemptive router (Nitro); a plain app falls through to 404.
      */
    val noContent: js.Any = null

    def errorBody(envelope: ErrorEnvelope, entries: List[ErrorEntry]): js.Any =
      envelopeOf(envelope, entries.map(entry => summon[NativeConverter[ErrorEntry]].toNative(entry)))

    /** The opaque internal error. With diagnostics, its entry carries `debug.cause`. */
    def internalErrorBody(envelope: ErrorEnvelope, diagnostics: Option[String]): js.Any =
      val entry = js.Dynamic.literal(message = internalErrorMessage)
      diagnostics.foreach(cause => entry.debug = js.Dynamic.literal(cause = cause))
      envelopeOf(envelope, List(entry))

    val internalErrorMessage: String = "Internal server error"

    private def envelopeOf(envelope: ErrorEnvelope, entries: List[js.Any]): js.Any = (envelope, entries) match
        case (ErrorEnvelope.SingleOrList, List(single)) => js.Dynamic.literal(error = single)
        case _                                          => js.Dynamic.literal(errors = js.Array(entries*))

    /** Applies status, headers in order (Set replaces, Append adds) and cookies. */
    def writeHead(event: H3Event, head: ResponseHead): Unit =
      head.status.foreach(status => H3.setResponseStatus(event, status.code))
      head.headers.foreach { entry =>
        entry.mode match
            case HeaderMode.Set    => H3.setResponseHeader(event, entry.name, entry.value)
            case HeaderMode.Append => H3.appendResponseHeader(event, entry.name, entry.value)
      }
      head.cookies.foreach { entry =>
        entry.cookie match
            case cookie: SetCookie    => H3.setCookie(event, cookie.name, cookie.value, cookieOptions(cookie))
            case cookie: DeleteCookie =>
              val options = pathAndDomain(cookie.path, cookie.domain).asInstanceOf[js.Dynamic]
              if cookie.secure then options.secure = true
              H3.deleteCookie(event, cookie.name, options.asInstanceOf[js.Object])
      }

    private def cookieOptions(cookie: SetCookie): js.Object =
      val options = pathAndDomain(cookie.path, cookie.domain).asInstanceOf[js.Dynamic]
      cookie.maxAge.foreach(seconds => options.maxAge = seconds.toDouble)
      options.httpOnly = cookie.httpOnly
      options.secure = cookie.secure
      options.sameSite = cookie.sameSite match
          case SameSite.Strict => "strict"
          case SameSite.Lax    => "lax"
          case SameSite.None   => "none"
      options.asInstanceOf[js.Object]

    private def pathAndDomain(path: Option[String], domain: Option[String]): js.Object =
      val options = js.Dynamic.literal()
      path.foreach(value => options.path = value)
      domain.foreach(value => options.domain = value)
      options.asInstanceOf[js.Object]

    /** Removes headers this head would have set, so a failure response does not inherit them. */
    def clearHead(event: H3Event, head: ResponseHead): Unit =
      val response = event.node.res.asInstanceOf[js.Dynamic]
      if js.typeOf(response.removeHeader) == "function" then
          head.headers.map(_.name.toLowerCase).distinct.foreach(name => response.removeHeader(name))
          if head.cookies.nonEmpty then response.removeHeader("set-cookie")

    /** The text of a defect for development diagnostics: class, message, causes and stack. */
    def describe(error: Throwable): String =
      val writer = new StringWriter()
      error.printStackTrace(new PrintWriter(writer))
      writer.toString

    def logDefect(details: String): Unit =
      js.Dynamic.global.console.error("Unhandled h34s handler failure", details)
end Rendering
