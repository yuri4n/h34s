package dev.yuri4n.exts.kyoh3.internal

import dev.yuri4n.exts.h3.{ResponseHead, ServerEvent}
import dev.yuri4n.exts.kyoh3.{FetchError, FetchRequest, FetchResponse, HandlerTransport}
import kyo.{Abort, Chunk, Emit, Frame, Kyo, Stream, Tag, Var, `<`}
import org.getshaka.nativeconverter.NativeConverter

import scala.scalajs.js

// claude 2026-10-20: created: the server-sent event plumbing that both runtimes share: the head of sendEvents and the decoding of $fetch.events (ADR-0007)
/** Server-sent events on both sides: the head that `Response.sendEvents` sets, and how
  * `$fetch.events` checks a response and decodes its body. The ZIO adapter uses the same rules.
  */
private[exts] object EventStreams:
    /** The program's head with h3's event-stream headers set (`ServerEvent.headers`). They are set
      * like `Response.header`: they replace a value of the same name that the program or the
      * handler defaults set before, and every other entry stays.
      */
    def head(transport: HandlerTransport, head: ResponseHead): ResponseHead =
      ServerEvent.headers(http2 = isHttp2(transport)).foldLeft(head) { case (current, (name, value)) => current.withHeader(name, value) }

    /** h3's `isHttp2Request`: the request has the `:path` and `:method` pseudo-headers. A transport
      * without a request (the test helpers) is not HTTP/2.
      */
    private def isHttp2(transport: HandlerTransport): Boolean =
      transport.event != null && {
        val headers = transport.event.node.req.headers
        headers.contains(":path") && headers.contains(":method")
      }

    /** The request with `accept: text/event-stream`, unless it names an `accept` header itself. */
    def request(request: FetchRequest): FetchRequest =
      if request.headers.exists(_._1.equalsIgnoreCase("accept")) then request
      else request.copy(headers = request.headers :+ ("accept" -> "text/event-stream"))

    /** True when the media type of `content-type` is `text/event-stream`, parameters aside. */
    def isEventStream(headers: Map[String, String]): Boolean =
      headers.get("content-type").exists(_.split(';')(0).trim.equalsIgnoreCase("text/event-stream"))

    /** Why a response is not an event stream to decode: `FetchError.Status` for a status that is
      * not 2xx (with the text of a `Plain` body; a streamed body is not read), and
      * `FetchError.NotEvents` for another content type. `None` for an event stream.
      */
    def refused(status: Int, headers: Map[String, String], plainText: Option[String]): Option[FetchError] =
      if status < 200 || status > 299 then Some(FetchError.Status(status, plainText.getOrElse("")))
      else if !isEventStream(headers) then Some(FetchError.NotEvents(status, headers.get("content-type")))
      else None

    /** The events of a body of text chunks, decoded as they arrive. */
    def decode[S](body: Stream[String, S])(using Frame): Stream[ServerEvent, S] =
      Stream[ServerEvent, S](
        Var.run(ServerEvent.Decoder.initial)(
          body.foreachChunk(chunk =>
            Var.use[ServerEvent.Decoder.State] { state =>
              val (next, events) = feedAll(state, chunk)
              Var.setDiscard(next).andThen(if events.isEmpty then Kyo.unit else Emit.value(Chunk.from(events)))
            },
          ),
        ),
      )

    /** The decoder over every text of a chunk. Shared with the ZIO adapter. */
    def feedAll(state: ServerEvent.Decoder.State, texts: Iterable[String]): (ServerEvent.Decoder.State, Vector[ServerEvent]) =
      texts.foldLeft((state, Vector.empty[ServerEvent])) { case ((current, events), text) =>
        val (next, found) = ServerEvent.Decoder.feed(current, text)
        (next, events ++ found)
      }

    /** Each event's `data` decoded as JSON with the converter. The first event that does not
      * decode fails the stream with `FetchError.Decode`, after the events before it; the events
      * after it are not decoded.
      */
    def typed[E: {NativeConverter, Tag}, S](status: Int, events: Stream[ServerEvent, S])(using Frame): Stream[E, S & Abort[FetchError]] =
      Stream[E, S & Abort[FetchError]](
        Var.run(0L)(
          events.foreachChunk(chunk =>
            Var.use[Long] { count =>
              val (values, failure) =
                untilFailure(chunk.iterator.zipWithIndex.map((event, offset) => decodedEvent[E](status, event, count + offset + 1)))
              val emitted = if values.isEmpty then Kyo.unit else Emit.value(Chunk.from(values))
              Var.setDiscard(count + chunk.size).andThen(emitted).andThen(failure match
                  case Some(error) => Abort.fail(error)
                  case None        => Kyo.unit)
            },
          ),
        ),
      )

    /** The values before the first failure, and that failure. Stops reading `results` there. */
    private def untilFailure[E](results: Iterator[Either[FetchError, E]]): (Vector[E], Option[FetchError]) =
      val values                      = Vector.newBuilder[E]
      var failure: Option[FetchError] = None
      while failure.isEmpty && results.hasNext do
          results.next() match
              case Right(value) => values += value
              case Left(error)  => failure = Some(error)
      (values.result(), failure)

    /** One event's `data` as JSON for `E`, with `FetchResponse.decoded`. The reason names the
      * event by its position in the stream (from 1) and its type, never by its data: a converter's
      * message quotes the values it rejects.
      */
    def decodedEvent[E: NativeConverter](status: Int, event: ServerEvent, index: Long): Either[FetchError, E] =
      val named = s"event $index${event.event.fold("")(name => s" (${js.JSON.stringify(name)})")}"
      FetchResponse.decoded[E](status, event.data, s"the data of $named")
end EventStreams
