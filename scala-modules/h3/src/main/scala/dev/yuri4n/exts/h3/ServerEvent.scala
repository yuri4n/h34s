package dev.yuri4n.exts.h3

import org.getshaka.nativeconverter.NativeConverter

import scala.scalajs.js

// claude 2026-10-20: created: one server-sent event, the value that Response.sendEvents sends and $fetch.events reads (ADR-0007)
/** One server-sent event, as `Response.sendEvents` sends it and `$fetch.events` reads it.
  *
  *   - `data` is the event's text. Each of its lines becomes one `data:` line of the frame. The
  *     client joins them with LF, so a CR or a CRLF in `data` arrives as LF.
  *   - `event` is the event type. `None` is the default type, which a browser's `EventSource`
  *     calls `message`. A name is not empty and has no CR or LF.
  *   - `id` is the event's ID. A browser keeps it as `lastEventId` and sends it back in the
  *     `Last-Event-ID` header when it reconnects; `Some("")` clears it. It has no CR, LF or U+0000.
  *   - `retry` asks a browser to wait that many milliseconds before it reconnects. It is not
  *     negative.
  *
  * The constructor checks these rules and throws an `IllegalArgumentException` when one breaks,
  * because a line break inside `event` or `id` would end the field early and change the frame's
  * meaning. h34s neither escapes nor drops such a character. Read the events back with
  * `$fetch.events`: each one has the fields of its own frame (`id` is not carried over to later
  * events, as a browser's `lastEventId` is).
  *
  * {{{
  * Response.sendEvents(Stream.init(Seq(ServerEvent("hello"), ServerEvent.json(Tick(3), event = Some("tick")))))
  * }}}
  */
final case class ServerEvent(
    data: String,
    event: Option[String] = None,
    id: Option[String] = None,
    retry: Option[Int] = None,
):
    ServerEvent.check(data, event, id, retry)

// claude 2026-10-20: created: the JSON constructor, the frame encoder, h3's event-stream headers and the decoder (ADR-0007)
object ServerEvent:
    /** An event whose `data` is `value` as JSON text, written with its `NativeConverter`. The JSON
      * text is one line. `$fetch.events[A]` decodes it with the same converter; in a browser, use
      * `JSON.parse(message.data)`.
      *
      * A value that has no JSON text (it converts to `undefined`), or that holds `NaN` or an
      * infinite number, throws an `IllegalArgumentException`: JSON text cannot carry them.
      */
    def json[A](value: A, event: Option[String] = None, id: Option[String] = None)(using
        converter: NativeConverter[A],
    ): ServerEvent =
      ServerEvent(jsonText(converter.toNative(value)), event, id)

    /** The text/event-stream frame of `event`: an `id:`, `event:` and `retry:` line for each field
      * that is set, in this order (h3's `createEventStream` uses it too), then one `data:` line per
      * line of `data`, and an empty line that ends the event. Every field is written as
      * `name: value`. `data` is split at CRLF, CR and LF; an empty `data` is one empty `data:`
      * line, so the event is still dispatched.
      */
    def frame(event: ServerEvent): String =
      val text = new java.lang.StringBuilder
      def field(name: String, value: String): Unit = { text.append(name).append(": ").append(value).append('\n'); () }
      event.id.foreach(field("id", _))
      event.event.foreach(field("event", _))
      event.retry.foreach(retry => field("retry", retry.toString))
      lines(event.data).foreach(field("data", _))
      text.append('\n').toString

    /** The response headers of h3 1.15's `createEventStream` (`setEventStreamHeaders`): the event
      * stream content type, no caching or transformation by proxies, no nginx buffering, and
      * `connection: keep-alive` unless the request is HTTP/2, where the header is not allowed.
      */
    private[exts] def headers(http2: Boolean): List[(String, String)] =
      List(
        "content-type"      -> "text/event-stream",
        "cache-control"     -> "private, no-cache, no-store, no-transform, must-revalidate, max-age=0",
        "x-accel-buffering" -> "no",
      ) ++ (if http2 then Nil else List("connection" -> "keep-alive"))

    private def check(data: String, event: Option[String], id: Option[String], retry: Option[Int]): Unit =
      require(data != null, "A server-sent event's data is null")
      require(
        event.forall(name => name != null && name.nonEmpty && !breaksLine(name)),
        "A server-sent event's type must be a non-empty line without CR or LF; use None for the default type",
      )
      require(
        id.forall(value => value != null && !breaksLine(value) && value.indexOf('\u0000') < 0),
        "A server-sent event's id must be one line without CR, LF or U+0000",
      )
      require(retry.forall(_ >= 0), "A server-sent event's retry must not be negative")

    private def breaksLine(text: String): Boolean = text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0

    /** The lines of `text`, split at CRLF, CR and LF. An empty text is one empty line, and a line
      * break at the end gives an empty last line.
      */
    private[exts] def lines(text: String): Vector[String] =
      val found = Vector.newBuilder[String]
      var start = 0
      var index = 0
      while index < text.length do
          val char = text.charAt(index)
          if char == '\n' || char == '\r' then
              found += text.substring(start, index)
              if char == '\r' && index + 1 < text.length && text.charAt(index + 1) == '\n' then index += 1
              start = index + 1
          index += 1
      found += text.substring(start)
      found.result()

    /** `JSON.stringify` of a converted value, which must have JSON text and finite numbers. */
    private def jsonText(native: js.Any): String =
      val finite: js.Function2[String, js.Any, js.Any] = (key, value) =>
        if js.typeOf(value) == "number" && !java.lang.Double.isFinite(value.asInstanceOf[Double]) then
            val at = if key.isEmpty then "the top level" else s"key \"$key\""
            throw IllegalArgumentException(s"The event's value has $value at $at; JSON cannot represent it")
        else value
      // Through js.Dynamic: `js.JSON.stringify` is typed `String`, and its `undefined` result would
      // fail that cast before the check below.
      val text: js.Any = js.Dynamic.global.JSON.stringify(native, finite)
      if js.typeOf(text) != "string" then throw IllegalArgumentException("The event's value has no JSON text")
      text.asInstanceOf[String]

    // claude 2026-10-20: created: the incremental text/event-stream decoder of $fetch.events (ADR-0007; the WHATWG parsing rules)
    /** The text/event-stream parser of the WHATWG HTML standard ("Interpreting an event stream"),
      * as a pure function over text chunks: `feed(state, chunk)` gives the next state and the
      * events that the chunk completed. A chunk may end anywhere, also inside a line or between
      * the CR and the LF of a CRLF.
      *
      *   - One leading U+FEFF (byte order mark) is removed. Lines end with CRLF, CR or LF.
      *   - A line that starts with `:` is a comment. A line `name: value` sets a field; one space
      *     after the colon is removed, and a line without a colon is a field with an empty value.
      *   - `data` adds a line to the event's data. `event` sets the type (empty means the default
      *     type, `None`). `id` sets the ID unless the value holds U+0000. `retry` sets the retry
      *     time when the value is only ASCII digits that fit an `Int`. Other fields are ignored.
      *   - An empty line ends the event. An event without a `data` line is not dispatched.
      *   - The fields belong to one event: after it ends, the next one starts without them. A
      *     browser carries the ID over to later events as `lastEventId`; `ServerEvent` does not.
      *   - An event that the stream does not end with an empty line is never dispatched.
      */
    private[exts] object Decoder:
        /** The parser's state between chunks. `afterCR`: the last chunk ended with CR, so an LF at
          * the start of the next chunk belongs to that line ending. `pending`: the pieces of a line
          * that has not ended yet. `data`: the data lines of the current event.
          */
        final case class State(
            started: Boolean,
            afterCR: Boolean,
            pending: Vector[String],
            data: Vector[String],
            event: Option[String],
            id: Option[String],
            retry: Option[Int],
        )

        val initial: State = State(false, false, Vector.empty, Vector.empty, None, None, None)

        /** The next state, and the events that `chunk` completed, in order. */
        def feed(state: State, chunk: String): (State, Vector[ServerEvent]) =
          if chunk.isEmpty then (state, Vector.empty)
          else
              val text      = if !state.started && chunk.charAt(0) == '﻿' then chunk.substring(1) else chunk
              val events    = Vector.newBuilder[ServerEvent]
              var current   = state.copy(started = true, afterCR = false)
              var lineStart = if state.afterCR && text.nonEmpty && text.charAt(0) == '\n' then 1 else 0
              var index     = lineStart
              while index < text.length do
                  val char = text.charAt(index)
                  if char == '\n' || char == '\r' then
                      val line = (current.pending :+ text.substring(lineStart, index)).mkString
                      current = applyLine(current.copy(pending = Vector.empty), line, events)
                      if char == '\r' then
                          if index + 1 == text.length then current = current.copy(afterCR = true)
                          else if text.charAt(index + 1) == '\n' then index += 1
                      lineStart = index + 1
                  index += 1
              if lineStart < text.length then current = current.copy(pending = current.pending :+ text.substring(lineStart))
              (current, events.result())

        /** Applies one complete line. */
        private def applyLine(state: State, text: String, events: scala.collection.mutable.Builder[ServerEvent, ?]): State =
          if text.isEmpty then
              if state.data.nonEmpty then events += ServerEvent(state.data.mkString("\n"), state.event, state.id, state.retry)
              state.copy(data = Vector.empty, event = None, id = None, retry = None)
          else if text.charAt(0) == ':' then state
          else
              val colon         = text.indexOf(':')
              val (name, value) =
                if colon < 0 then (text, "")
                else
                    val rest = text.substring(colon + 1)
                    (text.substring(0, colon), if rest.startsWith(" ") then rest.substring(1) else rest)
              name match
                  case "data"  => state.copy(data = state.data :+ value)
                  case "event" => state.copy(event = Option.when(value.nonEmpty)(value))
                  case "id"    => if value.indexOf('\u0000') >= 0 then state else state.copy(id = Some(value))
                  case "retry" => retryOf(value).fold(state)(retry => state.copy(retry = Some(retry)))
                  case _       => state

        /** Only ASCII digits, as the standard asks, and a value that fits an `Int`. */
        private def retryOf(value: String): Option[Int] =
          if value.isEmpty || !value.forall(char => char >= '0' && char <= '9') then None
          else
              val significant = value.dropWhile(_ == '0')
              if significant.isEmpty then Some(0)
              else if significant.length > 10 then None
              else Some(significant.toLong).filter(_ <= Int.MaxValue).map(_.toInt)
    end Decoder
end ServerEvent
