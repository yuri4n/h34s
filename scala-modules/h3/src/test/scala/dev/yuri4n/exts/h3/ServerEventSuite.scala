package dev.yuri4n.exts.h3

import munit.FunSuite
import org.getshaka.nativeconverter.NativeConverter

import scala.util.Random

// claude 2026-10-20: created: the frame encoder and the incremental decoder of server-sent events (ADR-0007)
/** `ServerEvent`: its checks, its frame, and the decoder of `$fetch.events` against the WHATWG
  * parsing rules, with every split point of a text and a round-trip property.
  */
class ServerEventSuite extends FunSuite:
    final case class Tick(remaining: Int) derives NativeConverter
    final case class Reading(value: Double) derives NativeConverter

    import ServerEvent.Decoder

    /** The events of `chunks`, fed one after the other into one decoder. */
    private def decode(chunks: String*): Vector[ServerEvent] =
      chunks.foldLeft((Decoder.initial, Vector.empty[ServerEvent])) { case ((state, events), chunk) =>
        val (next, found) = Decoder.feed(state, chunk)
        (next, events ++ found)
      }._2

    /** What a client reads for `data`: SSE carries line breaks as LF only. */
    private def onTheWire(event: ServerEvent): ServerEvent =
      event.copy(data = event.data.replace("\r\n", "\n").replace('\r', '\n'))

    // ---- The value and its frame ----

    test("frame: id, event and retry when set, one data line per line of data, and an empty line at the end") {
      assertEquals(ServerEvent.frame(ServerEvent("hello")), "data: hello\n\n")
      assertEquals(
        ServerEvent.frame(ServerEvent("{\"remaining\":3}", Some("tick"), Some("7"), Some(1000))),
        "id: 7\nevent: tick\nretry: 1000\ndata: {\"remaining\":3}\n\n",
      )
      assertEquals(ServerEvent.frame(ServerEvent("a\nb")), "data: a\ndata: b\n\n")
      assertEquals(ServerEvent.frame(ServerEvent(" leading", id = Some(""))), "id: \ndata:  leading\n\n")
    }

    test("frame: CRLF and CR inside data split lines as LF does; an empty data and a data of newlines keep their lines") {
      assertEquals(ServerEvent.frame(ServerEvent("a\r\nb\rc")), "data: a\ndata: b\ndata: c\n\n")
      assertEquals(ServerEvent.frame(ServerEvent("")), "data: \n\n")
      assertEquals(ServerEvent.frame(ServerEvent("\n")), "data: \ndata: \n\n")
      assertEquals(ServerEvent.frame(ServerEvent("\r\n\r\n")), "data: \ndata: \ndata: \n\n")
      assertEquals(decode(ServerEvent.frame(ServerEvent(""))), Vector(ServerEvent("")))
      assertEquals(decode(ServerEvent.frame(ServerEvent("\n"))), Vector(ServerEvent("\n")))
      assertEquals(decode(ServerEvent.frame(ServerEvent("a\r\nb\rc"))), Vector(ServerEvent("a\nb\nc")))
    }

    test("a line break in event or id, an empty event, U+0000 in id, a negative retry or a null data is rejected") {
      val rejected = List(
        () => ServerEvent("x", event = Some("tick\nevent: other")),
        () => ServerEvent("x", event = Some("a\rb")),
        () => ServerEvent("x", event = Some("")),
        () => ServerEvent("x", id = Some("1\ndata: injected")),
        () => ServerEvent("x", id = Some("1\r")),
        () => ServerEvent("x", id = Some("a\u0000b")),
        () => ServerEvent("x", retry = Some(-1)),
        () => ServerEvent(null),
        () => ServerEvent("x").copy(event = Some("a\nb")),
      )
      rejected.foreach(build => intercept[IllegalArgumentException](build()))
      assertEquals(ServerEvent("x", Some("a b: c"), Some(""), Some(0)).retry, Some(0))
    }

    test("json writes the value as one line of JSON text through its converter; NaN and undefined are rejected") {
      val event = ServerEvent.json(Tick(3), event = Some("tick"), id = Some("3"))
      assertEquals(event, ServerEvent("{\"remaining\":3}", Some("tick"), Some("3")))
      assertEquals(ServerEvent.frame(event), "id: 3\nevent: tick\ndata: {\"remaining\":3}\n\n")
      assertEquals(ServerEvent.json("two\nlines").data, "\"two\\nlines\"")
      val nan = intercept[IllegalArgumentException](ServerEvent.json(Reading(Double.NaN)))
      assert(nan.getMessage.contains("key \"value\""), nan.getMessage)
      intercept[IllegalArgumentException](ServerEvent.json(()))
    }

    test("the headers are h3's createEventStream headers; connection only without HTTP/2") {
      val cacheControl = "private, no-cache, no-store, no-transform, must-revalidate, max-age=0"
      assertEquals(
        ServerEvent.headers(http2 = false),
        List("content-type" -> "text/event-stream", "cache-control" -> cacheControl, "x-accel-buffering" -> "no", "connection" -> "keep-alive"),
      )
      assertEquals(ServerEvent.headers(http2 = true).map(_._1), List("content-type", "cache-control", "x-accel-buffering"))
    }

    // ---- The decoder: the WHATWG rules ----

    test("decoder: the input is decoded text, so a byte order mark is not removed; at the start it is part of the first field name") {
      assertEquals(decode("\uFEFFdata: a\n\ndata: b\n\n"), Vector(ServerEvent("b")), "the first field name starts with U+FEFF: an unknown field")
      assertEquals(decode("\uFEFF", "data: a\n\n"), Vector.empty)
      assertEquals(decode("data: a\n\n\uFEFFdata: b\n\n"), Vector(ServerEvent("a")))
      assertEquals(decode("data: \uFEFFa\n\n"), Vector(ServerEvent("\uFEFFa")))
    }

    test("decoder: lines end with CRLF, CR or LF, also when a CRLF is split between chunks") {
      assertEquals(decode("data: a\r\rdata: b\r\n\r\ndata: c\n\n"), Vector(ServerEvent("a"), ServerEvent("b"), ServerEvent("c")))
      assertEquals(decode("data: a\r", "\ndata: b\r\n\r\n"), Vector(ServerEvent("a\nb")))
      assertEquals(decode("data: a\r", "", "\n\r\n"), Vector(ServerEvent("a")))
      assertEquals(decode("data: a\r", "\r"), Vector(ServerEvent("a")), "a CR at the end of a chunk ends the line at once")
    }

    test("decoder: comments are ignored; field and value split at the first colon, without one leading space") {
      assertEquals(decode(": ping\n\n"), Vector.empty)
      assertEquals(decode(":\ndata: x\n\n"), Vector(ServerEvent("x")))
      assertEquals(decode("data:x\n\n"), Vector(ServerEvent("x")))
      assertEquals(decode("data:  x\n\n"), Vector(ServerEvent(" x")))
      assertEquals(decode("data: a: b\n\n"), Vector(ServerEvent("a: b")))
      assertEquals(decode("data : x\n\n"), Vector.empty, "the field name is \"data \", an unknown field")
      assertEquals(decode("data\n\n"), Vector(ServerEvent("")), "a line without a colon is a field with an empty value")
    }

    test("decoder: data lines join with LF; event, id and retry belong to their event") {
      assertEquals(decode("data: a\ndata: b\n\n"), Vector(ServerEvent("a\nb")))
      assertEquals(decode("data:\n\n"), Vector(ServerEvent("")))
      assertEquals(decode("data\ndata\n\n"), Vector(ServerEvent("\n")))
      assertEquals(
        decode("event: tick\nid: 1\nretry: 500\ndata: a\n\ndata: b\n\n"),
        Vector(ServerEvent("a", Some("tick"), Some("1"), Some(500)), ServerEvent("b")),
      )
      assertEquals(decode("event: tick\nevent:\ndata: x\n\n"), Vector(ServerEvent("x")), "an empty type is the default type")
      assertEquals(decode("id:\ndata: x\n\n"), Vector(ServerEvent("x", id = Some(""))))
    }

    test("decoder: an id with U+0000 and a retry with anything but digits are ignored; unknown fields too") {
      assertEquals(decode("id: 1\nid: a\u0000b\ndata: x\n\n"), Vector(ServerEvent("x", id = Some("1"))))
      assertEquals(decode("retry: 1a\ndata: x\n\n"), Vector(ServerEvent("x")))
      assertEquals(decode("retry: -1\ndata: x\n\n"), Vector(ServerEvent("x")))
      assertEquals(decode("retry:\ndata: x\n\n"), Vector(ServerEvent("x")))
      assertEquals(decode("retry: 0042\ndata: x\n\n"), Vector(ServerEvent("x", retry = Some(42))))
      assertEquals(decode("retry: 99999999999\ndata: x\n\n"), Vector(ServerEvent("x")), "a value that does not fit an Int")
      assertEquals(decode("foo: bar\nDATA: y\ndata: x\n\n"), Vector(ServerEvent("x")))
    }

    test("decoder: an empty line dispatches; an event without data is not dispatched and leaves nothing behind") {
      assertEquals(decode("event: tick\nid: 1\nretry: 9\n\n"), Vector.empty)
      assertEquals(decode("event: tick\nid: 1\n\ndata: x\n\n"), Vector(ServerEvent("x")))
      assertEquals(decode("\n\n\ndata: x\n\n\n"), Vector(ServerEvent("x")))
    }

    test("decoder: an event that the stream does not end with an empty line is never dispatched") {
      assertEquals(decode("data: a\n"), Vector.empty)
      assertEquals(decode("data: a\n\ndata: b"), Vector(ServerEvent("a")))
      val (state, events) = Decoder.feed(Decoder.initial, "data: a\ndata: b")
      assertEquals(events, Vector.empty)
      assertEquals(state.data, Vector("a"))
      assertEquals(state.pending, Vector("data: b"))
    }

    // ---- Split points and round trips ----

    private val text =
      ": a comment\r\nid: 1\r\nevent: tick\r\ndata: {\"remaining\":3}\r\n\r\n" +
        "data: two\rdata: lines\r\rretry: 100\ndata: é😀\n\n" +
        "event: ignored\n\ndata\n\nid: a\u0000b\ndata: last\n\n" + "data: never dispatched\n"

    private val expected = Vector(
      ServerEvent("{\"remaining\":3}", Some("tick"), Some("1")),
      ServerEvent("two\nlines"),
      ServerEvent("é😀", retry = Some(100)),
      ServerEvent(""),
      ServerEvent("last"),
    )

    test("decoder: the multi-event text gives its events in one chunk and one character at a time") {
      assertEquals(decode(text), expected)
      assertEquals(decode(text.map(_.toString)*), expected)
    }

    test("decoder: every split point of the multi-event text into two chunks gives the same events") {
      (0 to text.length).foreach { index =>
        assertEquals(decode(text.take(index), text.drop(index)), expected, s"split at $index")
      }
    }

    // Explicit, seeded generators.
    private val dataPieces  = List("a", "Z", " ", ":", "\n", "\r", "\r\n", "\u0000", "é", "😀", "data: ", "\uFEFF", "\t", "id")
    private val fieldPieces = List("a", "Z", " ", ":", "é", "😀", "\uFEFF", "\t", "data")

    private def randomText(random: Random, pieces: List[String], max: Int): String =
      List.fill(random.nextInt(max + 1))(pieces(random.nextInt(pieces.size))).mkString

    private def event(random: Random): ServerEvent =
      val name = Option.when(random.nextBoolean())(randomText(random, fieldPieces, 4)).filter(_.nonEmpty)
      val id   = Option.when(random.nextBoolean())(randomText(random, fieldPieces, 4))
      val retry =
        Option.when(random.nextInt(3) == 0)(random.nextInt(4) match
            case 0 => 0
            case 1 => Int.MaxValue
            case _ => random.nextInt(100000))
      ServerEvent(randomText(random, dataPieces, 8), name, id, retry)

    test("property: every event's frame decodes to the event, with CR and CRLF in data read as LF (200 samples)") {
      val random = Random(20261020L)
      (1 to 200).foreach { _ =>
        val sample = event(random)
        assertEquals(decode(ServerEvent.frame(sample)), Vector(onTheWire(sample)), sample.toString)
      }
    }

    test("property: a run of frames split at a random point decodes to the same events (200 samples)") {
      val random = Random(51L)
      (1 to 200).foreach { _ =>
        val samples = Vector.fill(1 + random.nextInt(4))(event(random))
        val frames  = samples.map(ServerEvent.frame).mkString
        val at      = random.nextInt(frames.length + 1)
        assertEquals(decode(frames.take(at), frames.drop(at)), samples.map(onTheWire), s"split at $at of $samples")
      }
    }
end ServerEventSuite
