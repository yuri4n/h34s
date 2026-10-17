package dev.yuri4n.exts.kyoh3.internal

import dev.yuri4n.exts.h3.{
  DeleteCookie,
  HeaderMode,
  InvalidInput,
  ResponseHead,
  SafeError,
  SameSite,
  SetCookie,
  Status,
}
import dev.yuri4n.exts.h3.internal.{H3, H3Event}

import java.io.{PrintWriter, StringWriter}
import scala.scalajs.js

/** Writes a response head and error bodies to the native h3 event. Called once per request. */
private[exts] object Rendering:
    /** The public messages of an approved error. */
    def messages(error: SafeError): List[String] = error match
        case InvalidInput(details) if details.nonEmpty => details
        case _: InvalidInput                           => List(InvalidInput.genericMessage)
        case other                                     => List(other.message)

    /** A safe error approved for the client: a 4xx or 5xx status with a non-null message. */
    def approved(error: SafeError): Option[Status] =
      Status(error.status).toOption.filter(status => status.code >= 400 && error.message != null)

    def errorBody(messages: List[String]): js.Dynamic =
      js.Dynamic.literal(errors = js.Array(messages*))

    val internalErrorBody: List[String] = List("Internal server error")

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
              H3.deleteCookie(event, cookie.name, pathAndDomain(cookie.path, cookie.domain))
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
