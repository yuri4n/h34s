package dev.yuri4n.exts.ntcvkyoschema

import dev.yuri4n.exts.h3.{ErrorEntry, InvalidInput, WireType}
import dev.yuri4n.exts.ntcvkyoschema.Models.*
import kyo.{Json, Schema, Span, Structure, Yaml}
import munit.{FunSuite, Location}
import org.getshaka.nativeconverter.NativeConverter

import java.time.{Duration, Instant}
import scala.scalajs.js
import scala.util.Random

class NativeSchemaSuite extends FunSuite:
    // ---- helpers ----

    private def decode[A](input: js.Any)(using converter: NativeConverter[A]): Either[InvalidInput, A] =
      try Right(converter.fromNative(input))
      catch case invalid: InvalidInput => Left(invalid)

    private val generic: Either[InvalidInput, Nothing] = Left(InvalidInput())

    /** The reference shape: what a JavaScript client gets from kyo-schema-json's text. */
    private def jsonWire[A: Schema](value: A): js.Any = js.JSON.parse(Json.encode(value))

    /** Deep equality with `Object.is` for scalars (NaN equals NaN, -0 differs from 0) and key order. */
    private def same(left: js.Any, right: js.Any): Boolean =
      if js.Array.isArray(left) || js.Array.isArray(right) then
          js.Array.isArray(left) && js.Array.isArray(right) && {
            val (a, b) = (left.asInstanceOf[js.Array[js.Any]], right.asInstanceOf[js.Array[js.Any]])
            a.length == b.length && a.indices.forall(index => same(a(index), b(index)))
          }
      else if isObject(left) || isObject(right) then
          isObject(left) && isObject(right) && {
            val (a, b) = (left.asInstanceOf[js.Dictionary[js.Any]], right.asInstanceOf[js.Dictionary[js.Any]])
            val keys = js.Object.keys(left.asInstanceOf[js.Object]).toList
            keys == js.Object.keys(right.asInstanceOf[js.Object]).toList && keys.forall(key => same(a(key), b(key)))
          }
      else js.Dynamic.global.Object.is(left, right).asInstanceOf[Boolean]

    private def isObject(value: js.Any): Boolean = value != null && js.typeOf(value) == "object"

    private def wireType[A](using converter: NativeConverter[A]): WireType =
      converter.asInstanceOf[WireType.Described].wireType

    /** The type describes what a client receives for each value. */
    private def described[A](values: A*)(using converter: NativeConverter[A], location: Location): Unit =
      values.foreach(value =>
        assert(wireType[A].describes(js.JSON.parse(js.JSON.stringify(converter.toNative(value)))), s"${wireType[A].render} for $value"),
      )

    private val decimal = "number | \"NaN\" | \"Infinity\" | \"-Infinity\""

    // ---- TypeScript types ----

    test("TypeScript types of records, options, numbers, scalars and collections follow the encoder") {
      assertEquals(
        wireType[Person].render,
        "{ name: string; age: number; address: { street: string; zip?: string | null }; tags: Array<string>; nickname?: string | null }",
      )
      described(Person("Ada", 36, Address("Main", None), Nil, None), Person("Bob", 1, Address("Side", Some("1")), List("x"), Some("b")))
      assertEquals(
        wireType[Numbers].render,
        s"{ int: number; long: number; double: $decimal; float: $decimal; short: number; byte: number }",
      )
      described(Numbers(1, 2L, Double.NaN, Float.PositiveInfinity, 3, 4), Numbers(1, 2L, 1.5, -0.5f, 3, 4))
      assertEquals(wireType[Bigs].render, "{ decimal: string; integer: string }")
      described(Bigs(BigDecimal("1.5"), BigInt(12)))
      assertEquals(wireType[Times].render, "{ at: string; took: string }")
      described(Times(Instant.parse("2026-01-02T03:04:05Z"), Duration.ofSeconds(1)))
      assertEquals(
        wireType[Collections].render,
        "{ list: Array<number>; vector: Array<string>; set: Array<number>; byName: Record<string, number>; byId: Array<{ key: number; value: string }> }",
      )
      described(Collections(List(1), Vector("a"), Set(2), Map("k" -> 3), Map(4 -> "v")), Collections(Nil, Vector.empty, Set.empty, Map.empty, Map.empty))
      assertEquals(wireType[Blob].render, "{ name: string; data: string }")
      assertEquals(wireType[Renamed].render, "{ full_name: string; age: number }")
      described(Renamed("Ada", 36))
    }

    test("TypeScript types of sealed traits and enums: one-key objects, or the @discriminator field") {
      val empty = "Record<string, never>"
      val color = s"{ Red: $empty } | { Green: $empty } | { Blue: $empty }"
      assertEquals(
        wireType[Drawing].render,
        s"{ title: string; shapes: Array<{ Circle: { radius: $decimal } } | { Rect: { width: $decimal; height: $decimal } } | { Empty: $empty }>; color: $color; background?: $color | null }",
      )
      described(Drawing("plan", List(Shape.Circle(1.5), Shape.Rect(1, 2), Shape.Empty), Color.Red, Some(Color.Blue)))
      assertEquals(
        wireType[Event].render,
        "{ type: \"Joined\"; user: string; seat: number } | { type: \"Left\"; user: string }",
      )
      described[Event](Event.Joined("ada", 3), Event.Left("bob"))
    }

    test("checks, validation and transforms that keep the shape keep the TypeScript type") {
      assertEquals(wireType[Signup].render, "{ name: string; email: string; age: number }")
      assertEquals(wireType[Strict].render, "{ name: string }")
      assertEquals(wireType[Credentials].render, "{ user: string; token: string }")
      assertEquals(NativeSchema[Message].wireType.render, "{ text: string }")
    }

    test("a schema whose encoder the structure does not show gets unknown, also when nested") {
      // The fluent rename writes `road`, not the `street` that the structure lists.
      assertEquals(NativeSchema[Road].wireType, WireType.Unknown)
      assertEquals(wireType[Trip], WireType.Unknown)
      described(Trip("t", Road("Main", None)))
    }

    // claude 2026-10-20: created: the audit of Kyo's names and the named models of the bridge (ADR-0014)
    test("records and sums are named models after their Scala type; a generic instantiation is written out") {
      // What Kyo's Structure gives: the simple name, the full name in the tag, the type arguments.
      val person = summon[Schema[Person]].structure.asInstanceOf[Structure.Type.Product]
      assertEquals((person.name, person.tag.show, person.typeParams.size), ("Person", "dev.yuri4n.exts.ntcvkyoschema.Models$.Person", 0))
      val page = summon[Schema[Page[Person]]].structure.asInstanceOf[Structure.Type.Product]
      assertEquals((page.name, page.typeParams.toList.collect { case product: Structure.Type.Product => product.name }), ("Page", List("Person")))
      val shape = summon[Schema[Shape]].structure.asInstanceOf[Structure.Type.Sum]
      assertEquals((shape.name, shape.typeParams.size), ("Shape", 0))

      wireType[Person] match
          case WireType.Named(name, _, scalaType) => assertEquals((name, scalaType), ("Person", Some("dev.yuri4n.exts.ntcvkyoschema.Models.Person")))
          case other                              => fail(s"not a named model: $other")
      assertEquals(wireType[Person].renderNamed, "Person")
      // A nested record that derives only a Schema is a model too, declared first.
      assertEquals(
        wireType[Person].models.map(model => (model.name, model.definition)),
        List(
          "Address" -> "{ street: string; zip?: string | null }",
          "Person"  -> "{ name: string; age: number; address: Address; tags: Array<string>; nickname?: string | null }",
        ),
      )
      // Sums are models; their variants keep today's rendering inside the sum.
      val empty = "Record<string, never>"
      assertEquals(
        wireType[Drawing].models.map(model => (model.name, model.definition)),
        List(
          "Shape"   -> s"{ Circle: { radius: $decimal } } | { Rect: { width: $decimal; height: $decimal } } | { Empty: $empty }",
          "Color"   -> s"{ Red: $empty } | { Green: $empty } | { Blue: $empty }",
          "Drawing" -> "{ title: string; shapes: Array<Shape>; color: Color; background?: Color | null }",
        ),
      )
      assertEquals(
        wireType[Event].models.map(model => (model.name, model.definition)),
        List("Event" -> "{ type: \"Joined\"; user: string; seat: number } | { type: \"Left\"; user: string }"),
      )
      // A generic instantiation is written out, with its models named inside.
      val pages = NativeSchema[Page[Person]]
      assertEquals(pages.wireType.renderNamed, "{ items: Array<Person>; total: number }")
      assertEquals(pages.wireType.models.map(_.name), List("Address", "Person"))
      locally {
        given NativeConverter[Page[Person]] = pages
        described(Page(List(Person("Ada", 36, Address("Main", None), Nil, None)), 1))
      }
      // A recursive Kyo model has an endless structure: it stays unknown.
      assertEquals(wireType[Tree], WireType.Unknown)
    }

    private def show(value: js.Any): String = js.JSON.stringify(value)

    private def assertWire[A: {Schema, NativeConverter}](value: A)(using Location): Unit =
      val actual   = value.toNative
      val expected = jsonWire(value)
      assert(same(actual, expected), s"native ${show(actual)} != JSON ${show(expected)} for $value")

    private def assertRoundTrip[A: NativeConverter](value: A)(using Location): Unit =
      assertEquals(decode[A](value.toNative), Right(value))

    private def literal(fields: (String, js.Any)*): js.Any = js.Dictionary(fields*)

    private def personInput(fields: (String, js.Any)*): js.Any =
      val base = js.Dictionary[js.Any](
        "name"    -> "Ada",
        "age"     -> 36,
        "address" -> literal("street" -> "Main", "zip" -> null),
        "tags"    -> js.Array[js.Any]("a"),
      )
      fields.foreach((key, value) => base(key) = value)
      base

    // ---- seeded generators (property-style) ----

    private val random = Random(20261017L)

    private def pick[A](values: A*): A = values(random.nextInt(values.size))

    private def text(): String =
      pick("", "Ada", "naïve café", "quote\" and \\ slash", "emoji 🎉", "line\nbreak\ttab", "\u0000nul", random.alphanumeric.take(random.nextInt(12)).mkString)

    private def person(): Person =
      Person(
        name = text(),
        age = pick(Int.MinValue, Int.MaxValue, 0, -1, random.nextInt()),
        address = Address(text(), Option.when(random.nextBoolean())(text())),
        tags = List.fill(random.nextInt(4))(text()),
        nickname = Option.when(random.nextBoolean())(text()),
      )

    private val maxSafe = 9007199254740991L

    private def numbers(): Numbers =
      Numbers(
        int = pick(Int.MinValue, Int.MaxValue, 0, -1, random.nextInt()),
        long = pick(-maxSafe, maxSafe, 0L, random.nextLong() % maxSafe),
        double = pick(0.0, -0.0, 0.1, 1e21, 1e-7, Double.MaxValue, Double.MinPositiveValue, -123.456, random.nextGaussian() * 1e6, random.nextDouble()),
        float = pick(0.0f, -0.0f, 0.1f, 3.14159f, Float.MaxValue, Float.MinPositiveValue, 1e10f, 16777217f, random.nextFloat() * 1000),
        short = pick(Short.MinValue, Short.MaxValue, 0.toShort, random.nextInt(1000).toShort),
        byte = pick(Byte.MinValue, Byte.MaxValue, 0.toByte, random.nextInt(100).toByte),
      )

    private def shape(): Shape =
      pick(Shape.Circle(random.nextDouble() * 10), Shape.Rect(random.nextInt(50).toDouble, 0.5), Shape.Empty)

    private def drawing(): Drawing =
      Drawing(text(), List.fill(random.nextInt(4))(shape()), pick(Color.Red, Color.Green, Color.Blue), Option.when(random.nextBoolean())(Color.Blue))

    private def collections(): Collections =
      Collections(
        list = List.fill(random.nextInt(4))(random.nextInt()),
        vector = Vector.fill(random.nextInt(3))(text()),
        set = Set.fill(random.nextInt(4))(random.nextInt(100)),
        byName = Map.from(List.fill(random.nextInt(3))(text() -> random.nextInt())),
        byId = Map.from(List.fill(random.nextInt(3))(random.nextInt() -> text())),
      )

    private val samples = 200

    // ---- wire shape and round trips ----

    test("records, nested records, options and lists: native value equals JSON.parse(Json.encode(x)) and round-trips") {
      (1 to samples).foreach { _ =>
        val value = person()
        assertWire(value)
        assertRoundTrip(value)
      }
    }

    test("enums and sealed traits with data (external tagging) match kyo-schema-json and round-trip") {
      (1 to samples).foreach { _ =>
        val value = drawing()
        assertWire(value)
        assertRoundTrip(value)
      }
      assertWire[Shape](Shape.Empty)
      assertWire[Color](Color.Green)
      assertRoundTrip[Shape](Shape.Rect(1, 2))
    }

    test("List, Vector, Set, Map[String, A] and Map[Int, A] (empty and not) match kyo-schema-json and round-trip") {
      val empty = Collections(Nil, Vector.empty, Set.empty, Map.empty, Map.empty)
      assertWire(empty)
      assertRoundTrip(empty)
      (1 to samples).foreach { _ =>
        val value = collections()
        assertWire(value)
        assertRoundTrip(value)
      }
    }

    test("numeric edges: Int and Short/Byte ranges, safe Long, Double and Float (shortest form) match and round-trip") {
      (1 to samples).foreach { _ =>
        val value = numbers()
        assertWire(value)
        assertRoundTrip(value)
      }
    }

    test("NaN and Infinity are the strings kyo-schema-json writes, and decode back") {
      val specials = List(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity)
      specials.foreach { special =>
        val value = Numbers(1, 2L, special, special.toFloat, 3, 4)
        assertWire(value)
        val decoded = decode[Numbers](value.toNative).toOption.get
        assert(decoded.double.equals(special) && decoded.float.equals(special.toFloat), s"$special decoded as $decoded")
      }
      assertEquals(Numbers(0, 0L, Double.NaN, 0f, 0, 0).toNative.asInstanceOf[js.Dynamic].double.asInstanceOf[Any], "NaN")
    }

    test("a Long beyond 2^53 encodes like JSON.parse would, but is not decoded (it is not exact in JavaScript)") {
      val large = Numbers(0, Long.MaxValue, 0, 0, 0, 0)
      assertWire(large)
      assertEquals(decode[Numbers](large.toNative), generic)
      assertEquals(decode[Numbers](Numbers(0, -maxSafe, 0, 0, 0, 0).toNative).map(_.long), Right(-maxSafe))
    }

    test("BigDecimal and BigInt are strings, Instant and Duration are ISO strings, bytes are base64") {
      val bigs = Bigs(BigDecimal("123456789.000000000000000000000000000001"), BigInt("-123456789012345678901234567890"))
      assertWire(bigs)
      assertRoundTrip(bigs)
      assertEquals(bigs.toNative.asInstanceOf[js.Dynamic].integer.asInstanceOf[Any], "-123456789012345678901234567890")

      val times = Times(Instant.parse("2026-10-17T08:30:15.123456789Z"), Duration.ofMillis(90061001))
      assertWire(times)
      assertRoundTrip(times)

      val blob = Blob("bin", Span.fromUnsafe(Array[Byte](0, 1, -1, 127, -128, 42)))
      assertWire(blob)
      val decoded = decode[Blob](blob.toNative).toOption.get
      assertEquals(decoded.data.toArrayUnsafe.toList, blob.data.toArrayUnsafe.toList)
    }

    test("string-encoded BigInt and BigDecimal above the length or exponent limits are rejected before parsing") {
      def bigs(decimal: String, integer: String): js.Any = js.Dynamic.literal(decimal = decimal, integer = integer)
      val started = System.currentTimeMillis()
      assertEquals(decode[Bigs](bigs("1", "9" * 100000)), generic)
      assertEquals(decode[Bigs](bigs("1" * 100000, "1")), generic)
      assertEquals(decode[Bigs](bigs("1e999999999", "1")), generic)
      assertEquals(decode[Bigs](bigs("1e-10001", "1")), generic)
      assert(System.currentTimeMillis() - started < 1000, "rejections must not parse the text")
      assertEquals(decode[Bigs](bigs("1.5e10000", "9" * 1000)).map(_.integer), Right(BigInt("9" * 1000)))
      assertEquals(decode[Bigs](bigs("2E+3", "1")).map(_.decimal), Right(BigDecimal("2E+3")))
    }

    test("Secret fields with the bridge's Schema[Secret]: a string on the wire, never in an error") {
      val credentials = Credentials("alice", dev.yuri4n.exts.h3.Secret("t0k3n"))
      assertEquals(credentials.toNative.asInstanceOf[js.Dynamic].token.asInstanceOf[String], "t0k3n")
      val decoded = decode[Credentials](js.Dynamic.literal(user = "bob", token = "s3cr3t")).toOption.get
      assert(decoded.token.matches("s3cr3t"))
      assertEquals(decoded.toString, "Credentials(bob,Secret(<redacted>))")
      val error = intercept[InvalidInput](summon[NativeConverter[Credentials]].fromNative(js.Dynamic.literal(user = "bob", token = 12345)))
      assertEquals(error, InvalidInput())
      assert(!error.getMessage.contains("12345"))
    }

    test("@rename, @alias and @discriminator shapes match kyo-schema-json, and the hint still checks their numbers") {
      val renamed = Renamed("Ada", 36)
      assertWire(renamed)
      assertRoundTrip(renamed)
      assertEquals(decode[Renamed](literal("full_name" -> "Ada", "years" -> 3)), Right(Renamed("Ada", 3)))
      assertEquals(decode[Renamed](literal("full_name" -> "Ada", "age" -> 1.5)), generic)
      assertEquals(decode[Renamed](literal("full_name" -> "Ada", "years" -> 1.5)), generic)

      val events: List[Event] = List(Event.Joined("ada", 3), Event.Left("bob"))
      events.foreach { event =>
        assertWire(event)
        assertRoundTrip(event)
      }
      assertEquals(decode[Event](literal("type" -> "Joined", "user" -> "ada", "seat" -> 1.5)), generic)
    }

    test("pinned shapes: what a TypeScript client sees for options, variants, enums and maps") {
      val drawing     = Drawing("plan", List(Shape.Circle(1.5), Shape.Empty), Color.Red, None)
      val collections = Collections(List(1), Vector("a"), Set(2), Map("k" -> 3), Map(4 -> "v"))
      val person      = Person("Ada", 36, Address("Main", None), Nil, None)
      val event: Event = Event.Joined("ada", 3)
      // None is an absent key; variants and enum cases are single-key objects; non-string map keys are pairs.
      assertEquals(show(drawing.toNative), """{"title":"plan","shapes":[{"Circle":{"radius":1.5}},{"Empty":{}}],"color":{"Red":{}}}""")
      assertEquals(show(collections.toNative), """{"list":[1],"vector":["a"],"set":[2],"byName":{"k":3},"byId":[{"key":4,"value":"v"}]}""")
      assertEquals(show(person.toNative), """{"name":"Ada","age":36,"address":{"street":"Main"},"tags":[]}""")
      assertEquals(show(event.toNative), """{"type":"Joined","user":"ada","seat":3}""")
      List(show(jsonWire(drawing)), show(jsonWire(collections)), show(jsonWire(person)), show(jsonWire(event))).zip(
        List(drawing.toNative, collections.toNative, person.toNative, event.toNative).map(show),
      ).foreach((json, native) => assertEquals(native, json))
    }

    test("Option: an absent key, null and undefined decode to None; Some round-trips") {
      assertEquals(decode[Person](personInput()).map(_.nickname), Right(None))
      assertEquals(decode[Person](personInput("nickname" -> null)).map(_.nickname), Right(None))
      assertEquals(decode[Person](personInput("nickname" -> js.undefined)).map(_.nickname), Right(None))
      assertEquals(decode[Person](personInput("nickname" -> "ace")).map(_.nickname), Right(Some("ace")))
      assertWire(Person("Ada", 36, Address("Main", None), Nil, None))
    }

    // ---- decode failures: always the generic InvalidInput, never input values ----

    test("structural failures are the generic InvalidInput") {
      val inputs: List[(String, js.Any)] = List(
        "null"                  -> null,
        "undefined"             -> js.undefined,
        "array"                 -> js.Array[js.Any](),
        "string"                -> "Ada",
        "number"                -> 42,
        "boolean"               -> true,
        "Date"                  -> new js.Date(),
        "function"              -> ((() => 1): js.Function0[Int]),
        "missing name"          -> { val input = personInput(); input.asInstanceOf[js.Dictionary[js.Any]] -= "name"; input },
        "name is a number"      -> personInput("name" -> 42),
        "age is a fraction"     -> personInput("age" -> 1.5),
        "age above Int"         -> personInput("age" -> 2147483648.0),
        "age is NaN"            -> personInput("age" -> Double.NaN),
        "age is a string"       -> personInput("age" -> "36"),
        "tags is a string"      -> personInput("tags" -> "a"),
        "address is null"       -> personInput("address" -> null),
        "address is a Date"     -> personInput("address" -> new js.Date()),
        "deep unknown field"    -> personInput("extra" -> (1 to 600).foldLeft[js.Any](0)((inner, _) => js.Array(inner))),
      )
      inputs.foreach { (label, input) =>
        assertEquals(decode[Person](input), generic, label)
      }
    }

    test("strict scalars: integer ranges, safe Long, special-value strings, ISO strings, decimal strings, base64") {
      def numbersInput(fields: (String, js.Any)*): js.Any =
        val base = js.Dictionary[js.Any]("int" -> 1, "long" -> 2, "double" -> 3, "float" -> 4, "short" -> 5, "byte" -> 6)
        fields.foreach((key, value) => base(key) = value)
        base
      assertEquals(decode[Numbers](numbersInput("long" -> 9007199254740992.0)), generic)
      assertEquals(decode[Numbers](numbersInput("byte" -> 128)), generic)
      assertEquals(decode[Numbers](numbersInput("short" -> 40000)), generic)
      assertEquals(decode[Numbers](numbersInput("double" -> "nan")), generic)
      assertEquals(decode[Numbers](numbersInput("int" -> "NaN")), generic)
      assert(decode[Numbers](numbersInput("double" -> "-Infinity")).exists(_.double == Double.NegativeInfinity))
      assertEquals(decode[Numbers](numbersInput("double" -> 1)).map(_.double), Right(1.0))
      assertEquals(decode[Times](literal("at" -> "yesterday", "took" -> "PT1S")), generic)
      assertEquals(decode[Times](literal("at" -> "2026-10-17T00:00:00Z", "took" -> "1s")), generic)
      assertEquals(decode[Bigs](literal("decimal" -> "1.5", "integer" -> "1.5")), generic)
      assertEquals(decode[Bigs](literal("decimal" -> "abc", "integer" -> "1")), generic)
      assertEquals(decode[Blob](literal("name" -> "bin", "data" -> "%%%")), generic)
      assertEquals(decode[Color](literal("Purple" -> literal())), generic)
    }

    test("plain objects only; Object.create(null) is a plain object; unknown fields follow the schema's policy") {
      val bare = js.Object.create(null).asInstanceOf[js.Dictionary[js.Any]]
      bare("name") = "Ada"
      assertEquals(decode[Strict](bare), Right(Strict("Ada")))
      assertEquals(decode[Person](personInput("admin" -> true)).map(_.name), Right("Ada"))
      assertEquals(decode[Strict](literal("name" -> "Ada", "admin" -> true)), generic)
    }

    // ---- validation ----

    test("validation failures carry the schema's messages and field paths, in check order") {
      assertEquals(decode[Signup](literal("name" -> "Ada", "email" -> "ada@example.com", "age" -> 36)), Right(Signup("Ada", "ada@example.com", 36)))
      assertEquals(
        decode[Signup](literal("name" -> "   ", "email" -> "nobody", "age" -> 17)),
        Left(InvalidInput(List(
          ErrorEntry("must not be blank", Some("name")),
          ErrorEntry("must be at least 18", Some("age")),
          ErrorEntry("email must contain @", None),
        ))),
      )
      assertEquals(
        decode[Order](literal("customer" -> literal("name" -> ""), "items" -> js.Array[js.Any]())).left.map(_.entries.map(_.path)),
        Left(List(Some("customer.name"), Some("items"))),
      )
      assertEquals(
        decode[Order](literal("customer" -> literal("name" -> ""), "items" -> js.Array[js.Any]("x"))),
        Left(InvalidInput(List(ErrorEntry("customer name is required", Some("customer.name"))))),
      )
    }

    test("validation messages never contain the input, and a structural failure is reported before checks") {
      val secret = "s3cret-input-value-xyz"
      val failure = decode[Signup](literal("name" -> secret, "email" -> secret, "age" -> 1)).left.toOption.get
      assertEquals(failure.entries.map(_.path), List(Some("name"), Some("age"), None))
      assert(failure.errors.forall(entry => !entry.message.contains(secret) && !entry.path.exists(_.contains(secret))), failure.errors)
      assertEquals(decode[Signup](literal("name" -> 1, "email" -> "", "age" -> 1)), generic)
    }

    test("a throwing schema transform is a defect; a rejecting constructor is invalid input") {
      assertEquals(decode[Port](5), Right(Port(5)))
      assertEquals(decode[Port]("five"), generic)
      assert(summon[NativeConverter[Port]].isInstanceOf[NativeSchema[?]])
      val defect = intercept[IllegalStateException](NativeSchema[Port].fromNative(-1: js.Any))
      assertEquals(defect.getMessage, "private defect")
      assertEquals(decode[Even](literal("value" -> 4)).map(_.value), Right(4))
      assertEquals(decode[Even](literal("value" -> 3)), generic)
    }

    // ---- coexistence and no JSON text ----

    test("no blanket instance: a separately derived NativeConverter stays the type's converter (N12)") {
      val original = summon[NativeConverter[Message]]
      assert(!original.isInstanceOf[NativeSchema[?]], "Message keeps its own derived converter")
      val bridged = NativeSchema[Message]
      val message = Message("hello")
      assertEquals(original.fromNative(original.toNative(message)), message)
      assertEquals(bridged.fromNative(bridged.toNative(message)), message)
      assert(summon[NativeConverter[Person]].isInstanceOf[NativeSchema[?]], "derives NativeSchema is the type's converter")
      assert(!summon[NativeConverter[Address]].isInstanceOf[NativeSchema[?]], "a Schema alone does not install a NativeSchema")
    }

    test("no JSON text round trip: works with JSON.stringify and JSON.parse replaced by throwing functions (N13)") {
      val json       = js.Dynamic.global.JSON
      val stringify  = json.stringify
      val parse      = json.parse
      val prohibited: js.Function1[js.Any, Nothing] = _ => throw IllegalStateException("JSON text conversion was used")
      val people   = List.fill(10)(person())
      val drawings = List.fill(10)(drawing())
      try
          json.updateDynamic("stringify")(prohibited)
          json.updateDynamic("parse")(prohibited)
          people.foreach(value => assert(decode[Person](value.toNative) == Right(value)))
          drawings.foreach(value => assert(decode[Drawing](value.toNative) == Right(value)))
          assert(decode[Signup](literal("name" -> "", "email" -> "a@b", "age" -> 40)).isLeft)
          assert(decode[Person](personInput("age" -> 1.5)) == generic)
      finally
          json.updateDynamic("stringify")(stringify)
          json.updateDynamic("parse")(parse)
    }

    test("the same schema also round-trips through kyo-schema-json and kyo-schema-yaml") {
      val people = List(
        Person("Ada", 36, Address("Main 1", Some("12345")), List("math", "engines"), Some("Countess")),
        Person("Bob", -1, Address("", None), Nil, None),
      )
      val drawings = List(
        Drawing("plan", List(Shape.Circle(1.5), Shape.Rect(2, 3), Shape.Empty), Color.Red, Some(Color.Blue)),
        Drawing("", Nil, Color.Green, None),
      )
      people.foreach { value =>
        assertEquals(Json.decode[Person](Json.encode(value)).toEither, Right(value))
        assertEquals(summon[Schema[Person]].decodeString[Yaml](summon[Schema[Person]].encodeString[Yaml](value)).toEither, Right(value))
      }
      drawings.foreach { value =>
        assertEquals(summon[Schema[Drawing]].decodeString[Yaml](summon[Schema[Drawing]].encodeString[Yaml](value)).toEither, Right(value))
        assertRoundTrip(value)
      }
    }
end NativeSchemaSuite
