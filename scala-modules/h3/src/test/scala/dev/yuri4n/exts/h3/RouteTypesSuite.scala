package dev.yuri4n.exts.h3

import dev.yuri4n.exts.h3.internal.HandlerMetadata
import munit.FunSuite
import org.getshaka.nativeconverter.{NativeConverter, ParseState}

import scala.scalajs.js

final case class Point(x: Int, y: Int) derives NativeConverter

final case class Email(value: String)

object Email:
    given NativeConverter[Email] = new NativeConverter[Email] with WireType.Described:
        val wireType: WireType = WireType.StringValue
        extension (email: Email) def toNative: js.Any = email.value
        def fromNative(state: ParseState): Email = Email(state.json.asInstanceOf[String])

class RouteTypesSuite extends FunSuite:
    /** What the client receives: the converted value after JSON text. */
    private def onTheWire[A](value: A)(using converter: NativeConverter[A]): js.Any =
      js.JSON.parse(js.JSON.stringify(converter.toNative(value)))

    private def shape[A](using shape: WireShape[A], converter: NativeConverter[A]): WireType = shape.of(converter)

    private def handler(run: js.Function1[js.Any, js.Any]): EventHandler = EventHandler.fromNative(run)

    // ---- Route ----

    test("routes carry Nitro's path syntax and an optional method") {
      assertEquals(Route.get("/api/notes").method, Some(Method.Get))
      assertEquals(Route.post("/api/notes/:id").path, "/api/notes/:id")
      assertEquals(Route.any("/api/files/**").method, None)
      assertEquals(Route(Method.Patch, "/").toString, "PATCH /")
      assertEquals(Route.any("/api").toString, "* /api")
      assertEquals(Method.values.map(_.wireName).toList, List("get", "head", "post", "put", "patch", "delete", "options"))
    }

    test("invalid route paths fail when the route is built, with the reason") {
      for (path, reason) <- List(
          ""             -> "must not be empty",
          "api/notes"    -> "must start with '/'",
          "/api/no tes"  -> "spaces or control characters",
          "/api/notes?x" -> "must not contain '?'",
          "/api/#"       -> "must not contain '?'",
          "/api\\notes"  -> "must not contain '?'",
          "/api//notes"  -> "empty segments",
          "/api/notes/"  -> "empty segments",
        )
      do
          assert(Route.problem(path).exists(_.contains(reason)), s"$path: ${Route.problem(path)}")
          intercept[IllegalArgumentException](Route.get(path))
      assertEquals(Route.problem("/"), None)
    }

    test("at gives a new handler that runs the original and carries the route; the original is unchanged") {
      val calls    = js.Array[js.Any]()
      val original = handler(event => { calls.push(event); "answer" })
      val mounted  = original.at(Route.post("/api/greeting"))
      assert(!js.special.strictEquals(mounted.toNative, original.toNative))
      assertEquals(mounted.toNative.asInstanceOf[js.Function1[js.Any, js.Any]]("event").asInstanceOf[String], "answer")
      assertEquals(calls.toList, List[js.Any]("event"))
      val record = HandlerMetadata.read(mounted.toNative).get
      assertEquals(record.version.asInstanceOf[Int], 1)
      assertEquals(record.route.asInstanceOf[String], "/api/greeting")
      assertEquals(record.method.asInstanceOf[String], "post")
      assert(js.isUndefined(record.types))
      assertEquals(HandlerMetadata.read(original.toNative), None)
      assertEquals(mounted.toNative.asInstanceOf[js.Dynamic].__is_handler__.asInstanceOf[Boolean], true)
      // Not enumerable: it never reaches a JSON body or a spread copy.
      assertEquals(js.Object.keys(mounted.toNative.asInstanceOf[js.Object]).toList, List("__is_handler__"))
      val any = HandlerMetadata.read(original.at(Route.any("/api/**")).toNative).get
      assert(js.isUndefined(any.method))
    }

    test("at keeps the response types of the handler definition") {
      val defined = HandlerMetadata.describe(
        handler(_ => "ok").toNative,
        HandlerOptions.default,
        internal.BodyShape.Value(() => WireType.StringValue),
      )
      val record = HandlerMetadata.read(EventHandler.fromNative(defined).at(Route.get("/x")).toNative).get
      val types  = record.types.asInstanceOf[js.Function0[js.Dynamic]]()
      assertEquals(types.success.asInstanceOf[String], "{ data: string }")
    }

    // claude 2026-10-19: created: Raw sends a value that converts to null as 204 without a body; $fetch then returns undefined
    test("Raw: a value that converts to null is no content, so its type is undefined, not null") {
      def success(options: HandlerOptions, wire: WireType): String =
        val described = HandlerMetadata.describe(handler(_ => "ok").toNative, options, internal.BodyShape.Value(() => wire))
        HandlerMetadata.read(described).get.types.asInstanceOf[js.Function0[js.Dynamic]]().success.asInstanceOf[String]
      val raw = HandlerOptions(success = SuccessEnvelope.Raw)
      assertEquals(success(raw, shape[Option[Int]]), "number | undefined")
      assertEquals(success(raw, WireType.NullValue), "undefined")
      assertEquals(success(raw, shape[Option[Option[String]]]), "string | undefined")
      assertEquals(success(raw, WireType.OneOf(List(WireType.NullValue, WireType.Unknown))), "unknown")
      // Only the top level is no content: JSON text keeps a null inside an array or an object.
      assertEquals(success(raw, shape[List[Option[Int]]]), "Array<number | null>")
      assertEquals(success(raw, shape[String]), "string")
      // The data envelope always has a body, so null stays null there.
      assertEquals(success(HandlerOptions.default, shape[Option[Int]]), "{ data: number | null }")
    }

    // ---- WireType ----

    test("renders TypeScript: quoted keys, optional fields, arrays, records, unions") {
      val wire = WireType.ObjectOf(List(
        WireField("id", WireType.NumberValue),
        WireField("full-name", WireType.StringValue),
        WireField("nick", WireType.nullable(WireType.StringValue), optional = true),
        WireField("tags", WireType.ArrayOf(WireType.OneOf(List(WireType.StringValue, WireType.NumberValue)))),
        WireField("byName", WireType.RecordOf(WireType.BooleanValue)),
        WireField("kind", WireType.StringLiteral("say \"hi\"\n")),
        WireField("empty", WireType.ObjectOf(Nil)),
      ))
      assertEquals(
        wire.render,
        """{ id: number; "full-name": string; nick?: string | null; tags: Array<string | number>; byName: Record<string, boolean>; kind: "say \"hi\"\n"; empty: Record<string, never> }""",
      )
      assertEquals(WireType.OneOf(List(WireType.OneOf(List(WireType.NullValue, WireType.StringValue)), WireType.NullValue)).render, "null | string")
      assertEquals(WireType.OneOf(List(WireType.StringValue, WireType.Unknown)).render, "unknown")
      assertEquals(WireType.OneOf(Nil).render, "never")
      assertEquals(WireType.StringLiteral("\u2028").render, "\"\\u2028\"")
    }

    test("describes checks values: missing optional fields pass, extra keys and wrong types fail") {
      val wire = WireType.ObjectOf(List(WireField("a", WireType.NumberValue), WireField("b", WireType.StringValue, optional = true)))
      assert(wire.describes(js.Dynamic.literal(a = 1)))
      assert(wire.describes(js.Dynamic.literal(a = 1, b = "x")))
      assert(!wire.describes(js.Dynamic.literal(a = "1")))
      assert(!wire.describes(js.Dynamic.literal(a = 1, c = 2)))
      assert(!wire.describes(js.Dynamic.literal(b = "x")))
      assert(!wire.describes(js.Array(1)))
      assert(!wire.describes(null))
      assert(WireType.ArrayOf(WireType.NumberValue).describes(js.Array(1, 2)))
      assert(!WireType.ArrayOf(WireType.NumberValue).describes(js.Array(1, "2")))
      assert(WireType.RecordOf(WireType.BooleanValue).describes(js.Dynamic.literal(x = true)))
      assert(!WireType.RecordOf(WireType.BooleanValue).describes(js.Array()))
      assert(WireType.nullable(WireType.StringValue).describes(null))
      assert(WireType.Unknown.describes(js.undefined))
    }

    // ---- WireShape over native-converter's own converters ----

    test("native-converter scalars: Long and Char are strings, numbers are numbers") {
      assertEquals(shape[String], WireType.StringValue)
      assertEquals(shape[Boolean], WireType.BooleanValue)
      for wire <- List(shape[Int], shape[Short], shape[Byte], shape[Double], shape[Float]) do assertEquals(wire, WireType.NumberValue)
      assertEquals(shape[Long], WireType.StringValue)
      assert(shape[Long].describes(onTheWire(9007199254740993L)))
      assertEquals(shape[Char], WireType.StringValue)
      assert(shape[Char].describes(onTheWire('x')))
    }

    test("native-converter collections, Option and string maps, checked against their output") {
      assertEquals(shape[List[Long]], WireType.ArrayOf(WireType.StringValue))
      assert(shape[List[Long]].describes(onTheWire(List(1L, 2L))))
      assertEquals(shape[Vector[Int]], WireType.ArrayOf(WireType.NumberValue))
      assertEquals(shape[Seq[String]], WireType.ArrayOf(WireType.StringValue))
      assertEquals(shape[Set[Boolean]], WireType.ArrayOf(WireType.BooleanValue))
      assertEquals(shape[Array[Double]], WireType.ArrayOf(WireType.NumberValue))
      assertEquals(shape[Option[Int]], WireType.nullable(WireType.NumberValue))
      assert(shape[Option[Int]].describes(onTheWire(Option.empty[Int])))
      assert(shape[Option[Int]].describes(onTheWire(Option(3))))
      assertEquals(shape[Map[String, List[Int]]], WireType.RecordOf(WireType.ArrayOf(WireType.NumberValue)))
      assert(shape[Map[String, List[Int]]].describes(onTheWire(Map("a" -> List(1)))))
      assertEquals(shape[List[ErrorEntry]].render, "Array<{ message: string; path?: string }>")
      assert(shape[List[ErrorEntry]].describes(onTheWire(List(ErrorEntry("m"), ErrorEntry("n", Some("p"))))))
    }

    test("a derived or hand-written converter is unknown, also inside a collection") {
      assertEquals(shape[Point], WireType.Unknown)
      assertEquals(shape[List[Point]], WireType.ArrayOf(WireType.Unknown))
      // A custom converter for the collection itself: its own output is not native-converter's.
      given custom: NativeConverter[List[Int]] with
          extension (list: List[Int]) def toNative: js.Any = list.mkString(",")
          def fromNative(state: ParseState): List[Int] = Nil
      assertEquals(shape[List[Int]], WireType.Unknown)
    }

    test("a converter that mixes in WireType.Described gives its own type") {
      assertEquals(shape[Email], WireType.StringValue)
      assertEquals(shape[Option[List[Email]]], WireType.nullable(WireType.ArrayOf(WireType.StringValue)))
    }
end RouteTypesSuite
