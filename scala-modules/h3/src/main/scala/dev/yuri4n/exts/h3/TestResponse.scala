package dev.yuri4n.exts.h3

// claude 2026-10-20: created: the result of KHandler.test and ZHandler.test (ADR-0006, Q10)
/** What a handler produced in a test with `KHandler.test` or `ZHandler.test`: the response head
  * that the program set, and its body.
  *
  * `head` is what your program set. Handler defaults (`HandlerOptions.defaults`), the default
  * content type and the envelope are added by h34s when it writes a real response, so they are not
  * in it.
  *
  * {{{
  * KHandler.test(handler)(Request.fake(path = "/api/greeting")).map { response =>
  *   response.status == Some(Status.Created) && response.body == TestResponse.Body.Value(Greeting("hi"))
  * }
  * }}}
  */
final case class TestResponse[+B](head: ResponseHead, body: TestResponse.Body[B]):
    def status: Option[Status] = head.status

    /** The values of a header after the program's sets and appends, in order. Names ignore case. */
    def headerValues(name: String): List[String] =
      head.headers.filter(_.name.equalsIgnoreCase(name)).foldLeft(List.empty[String]) { (values, entry) =>
        entry.mode match
            case HeaderMode.Set    => List(entry.value)
            case HeaderMode.Append => values :+ entry.value
      }

    /** The header's values joined with `", "`, as on the wire. */
    def header(name: String): Option[String] =
      Option(headerValues(name)).filter(_.nonEmpty).map(_.mkString(", "))

    def cookies: Vector[CookieEntry] = head.cookies

object TestResponse:
    /** What the handler returned, or the elements it sent with `Response.sendStream`. */
    enum Body[+B]:
        /** The value the handler returned (`()` for a `Unit` body, which h34s sends as a 204). */
        case Value(value: B)

        /** The strings of the stream that the handler sent, in order. */
        case Streamed(elements: List[String])

    /** The streamed elements when the handler sent a stream, else the value it returned. */
    private[exts] def collected[B](elements: Option[Vector[String]], value: B): Body[B] =
      elements.fold[Body[B]](Body.Value(value))(sent => Body.Streamed(sent.toList))
end TestResponse
