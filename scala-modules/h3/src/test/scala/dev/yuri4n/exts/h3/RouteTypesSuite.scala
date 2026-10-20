package dev.yuri4n.exts.h3

import dev.yuri4n.exts.h3.internal.HandlerMetadata
import munit.FunSuite
import org.getshaka.nativeconverter.{NativeConverter, ParseState}

import scala.scalajs.js
import scala.util.Random

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

    // ---- Named models (ADR-0014) ----

    // claude 2026-10-20: created: the Note fixture of the named-model tests (ADR-0014)
    private val noteFields = WireType.ObjectOf(List(WireField("id", WireType.NumberValue), WireField("text", WireType.StringValue)))
    private val note       = WireType.Named("Note", noteFields, Some("app.Note"))

    // claude 2026-10-20: created: the named rendering, the models and their order (ADR-0014)
    test("a named model renders as its name with renderNamed and as its structure with render") {
      val board = WireType.Named(
        "Board",
        WireType.ObjectOf(List(WireField("notes", WireType.ArrayOf(note)), WireField("pinned", WireType.nullable(note)))),
      )
      assertEquals(WireType.ArrayOf(note).render, "Array<{ id: number; text: string }>")
      assertEquals(WireType.ArrayOf(note).renderNamed, "Array<Note>")
      assertEquals(board.renderNamed, "Board")
      // A model comes after the models it uses, each once.
      assertEquals(
        WireType.ObjectOf(List(WireField("board", board), WireField("first", note))).models,
        List(
          WireModel("Note", "{ id: number; text: string }", Some("app.Note"), Nil),
          WireModel("Board", "{ notes: Array<Note>; pinned: Note | null }", None, List("Note")),
        ),
      )
      assert(board.describes(js.Dynamic.literal(notes = js.Array(js.Dynamic.literal(id = 1, text = "a")), pinned = null)))
      assert(!board.describes(js.Dynamic.literal(notes = js.Array(js.Dynamic.literal(id = "1", text = "a")), pinned = null)))
    }

    // claude 2026-10-20: created: recursion, unknown targets and names that are not exported (ADR-0014)
    test("a recursive model refers to its own name; unknown, invalid and reserved names are written out") {
      val tree = WireType.Named(
        "Tree",
        WireType.ObjectOf(List(
          WireField("value", WireType.NumberValue),
          WireField("children", WireType.ArrayOf(WireType.Named("Tree", WireType.Unknown, Some("app.Tree")))),
        )),
        Some("app.Tree"),
      )
      assertEquals(tree.renderNamed, "Tree")
      assertEquals(tree.models, List(WireModel("Tree", "{ value: number; children: Array<Tree> }", Some("app.Tree"), List("Tree"))))
      assertEquals(tree.render, "{ value: number; children: Array<unknown> }")
      // Outside its own model, a name without a structure is unknown and has no declaration.
      val opaque = WireType.Named("Opaque", WireType.Unknown)
      assertEquals((opaque.renderNamed, opaque.models), ("unknown", Nil))
      assertEquals(WireType.OneOf(List(opaque, WireType.StringValue)).renderNamed, "unknown")
      for name <- List("Record", "Array", "SuccessBody", "string", "class", "my-model", "1st", "") do
          val model = WireType.Named(name, WireType.ObjectOf(List(WireField("a", note))))
          assertEquals(model.renderNamed, "{ a: Note }", name)
          assertEquals(model.models.map(_.name), List("Note"), name)
    }

    // claude 2026-10-20: created: two models with one name: identical ones merge, different ones are both listed (ADR-0014)
    test("models with the same name: an identical structure is one declaration, a different one is listed again") {
      val other = WireType.Named("Note", WireType.ObjectOf(List(WireField("body", WireType.StringValue))), Some("other.Note"))
      val again = WireType.Named("Note", noteFields, Some("copy.Note"))
      assertEquals(WireType.OneOf(List(note, again)).models.map(model => (model.name, model.scalaType)), List(("Note", Some("app.Note"))))
      assertEquals(
        WireType.ObjectOf(List(WireField("a", note), WireField("b", other))).models.map(_.definition),
        List("{ id: number; text: string }", "{ body: string }"),
      )
      // A different model of the same name inside a model is its own model, not a recursion.
      val outer = WireType.Named("Note", WireType.ObjectOf(List(WireField("inner", other))), Some("app.Note"))
      assertEquals(outer.models.map(_.definition), List("{ body: string }", "{ inner: Note }"))
    }

    // claude 2026-10-20: created: the record's named success type and models (ADR-0014)
    test("types() also gives the success type with model names, the names it uses and the models") {
      def named(options: HandlerOptions, wire: WireType): (String, js.Dynamic) =
        val described = HandlerMetadata.describe(handler(_ => "ok").toNative, options, internal.BodyShape.Value(() => wire))
        val types     = HandlerMetadata.read(described).get.types.asInstanceOf[js.Function0[js.Dynamic]]()
        (types.success.asInstanceOf[String], types.named)
      val (inline, list) = named(HandlerOptions.default, WireType.ArrayOf(note))
      assertEquals(inline, "{ data: Array<{ id: number; text: string }> }")
      assertEquals(list.success.asInstanceOf[String], "{ data: Array<Note> }")
      assertEquals(list.uses.asInstanceOf[js.Array[String]].toList, List("Note"))
      assertEquals(js.JSON.stringify(list.models), """[{"name":"Note","type":"{ id: number; text: string }","uses":[],"scala":"app.Note"}]""")
      val raw = HandlerOptions(success = SuccessEnvelope.Raw)
      // Raw: the top-level null is no content, also inside a named model.
      assertEquals(named(raw, WireType.nullable(note))._2.success.asInstanceOf[String], "Note | undefined")
      assertEquals(named(raw, WireType.Named("Maybe", WireType.nullable(note)))._2.success.asInstanceOf[String], "Note | undefined")
      assertEquals(named(raw, WireType.Named("Maybe", WireType.nullable(note)))._1, "{ id: number; text: string } | undefined")
      assertEquals(named(raw, note)._2.success.asInstanceOf[String], "Note")
      val plain = named(HandlerOptions.default, WireType.StringValue)._2
      assertEquals((plain.success.asInstanceOf[String], plain.models.asInstanceOf[js.Array[js.Any]].length), ("{ data: string }", 0))
    }

    // ---- The inline rendering against its oracle (RenderOracle, commit 5f894e9) ----

    // claude 2026-10-20: created: the field names, model names and texts of the generator (ADR-0014)
    private val names  = List("id", "text", "full-name", "", "say \"hi\"", "a b", "_x", "$y")
    private val models = List("Note", "Board", "Tree", "Record", "my-model")
    private val texts  = List("", "a", "say \"hi\"\n", "\u2028", "\u0000", "naïve")

    // claude 2026-10-20: created: the generator of random wire types (ADR-0014)
    /** A random wire type; `named` also puts `Named` at random places. Explicit, seeded generators. */
    private def wire(random: Random, depth: Int, named: Boolean): WireType =
      def pick[A](values: List[A]): A = values(random.nextInt(values.size))
      val choices = if depth <= 0 then 6 else if named then 11 else 10
      random.nextInt(choices) match
          case 0 => WireType.Unknown
          case 1 => WireType.NullValue
          case 2 => WireType.StringValue
          case 3 => WireType.NumberValue
          case 4 => WireType.BooleanValue
          case 5 => WireType.StringLiteral(pick(texts))
          case 6 => WireType.ArrayOf(wire(random, depth - 1, named))
          case 7 =>
            WireType.ObjectOf(List.fill(random.nextInt(3))(WireField(pick(names), wire(random, depth - 1, named), random.nextBoolean())))
          case 8 => WireType.RecordOf(wire(random, depth - 1, named))
          case 9 => WireType.OneOf(List.fill(random.nextInt(4))(wire(random, depth - 1, named)))
          case _ => WireType.Named(pick(models), wire(random, depth - 1, named), pick(List(None, Some("a.Note"), Some("b.Note"))))

    // claude 2026-10-20: created: a type without its names, for the oracle (ADR-0014)
    /** The type without its names: what the oracle, which has no `Named`, can render. */
    private def plain(wire: WireType): WireType = wire match
        case WireType.Named(_, target, _) => plain(target)
        case WireType.ArrayOf(element)    => WireType.ArrayOf(plain(element))
        case WireType.ObjectOf(fields)    => WireType.ObjectOf(fields.map(field => field.copy(fieldType = plain(field.fieldType))))
        case WireType.RecordOf(value)     => WireType.RecordOf(plain(value))
        case WireType.OneOf(members)      => WireType.OneOf(members.map(plain))
        case other                        => other

    // claude 2026-10-20: created: the record's success type of a wire type (ADR-0014)
    private def success(options: HandlerOptions, wire: WireType): String =
      internal.RouteTypes.success(options.success, internal.BodyShape.Value(() => wire))

    // claude 2026-10-20: created: the options of a raw success body (ADR-0014)
    private val raw = HandlerOptions(success = SuccessEnvelope.Raw)

    // claude 2026-10-20: created: the oracle property of the inline rendering without names (ADR-0014)
    test("property: without names, render, renderNamed and the success types equal the oracle (200 samples)") {
      val random = Random(20261020L)
      (1 to 200).foreach { _ =>
        val sample = wire(random, 4, named = false)
        val shown  = RenderOracle.render(sample)
        assertEquals(sample.render, shown)
        assertEquals(sample.renderNamed, shown)
        assertEquals(sample.models, Nil)
        assertEquals(success(HandlerOptions.default, sample), s"{ data: $shown }")
        assertEquals(success(raw, sample), RenderOracle.raw(sample))
      }
    }

    // claude 2026-10-20: created: the oracle property of the inline rendering with names, and the closure of the models (ADR-0014)
    test("property: with names, render and the success types equal the oracle of the type without names (200 samples)") {
      val random = Random(66L)
      (1 to 200).foreach { _ =>
        val sample = wire(random, 5, named = true)
        assertEquals(sample.render, RenderOracle.render(plain(sample)), sample.toString)
        assertEquals(success(raw, sample), RenderOracle.raw(plain(sample)), sample.toString)
        // Every name that the named rendering or a model uses is declared.
        val declared = sample.models.map(_.name).toSet
        assert(WireType.references(sample).forall(declared), sample.toString)
        assert(sample.models.flatMap(_.uses).forall(declared), sample.toString)
      }
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
