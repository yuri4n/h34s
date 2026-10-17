package dev.yuri4n.exts.ntcvzioschema

import dev.yuri4n.exts.h3.{ErrorEntry, InvalidInput}
import org.getshaka.nativeconverter.NativeConverter
import zio.*
import zio.schema.{DeriveSchema, Schema, StandardType, derived}
import zio.schema.annotation.{fieldName, fieldNameAliases, rejectExtraFields, transientField, validate}
import zio.schema.validation.Validation
import zio.test.*

import scala.scalajs.js

object NativeSchemaSpec extends ZIOSpecDefault:
    final case class Pair(first: String, second: String)
    final case class Constrained(@validate(Validation.minLength(2)) text: String)
    final case class Nested(pair: Pair, names: List[String], count: Option[Int])
    final case class Defaults(text: String = "default", count: Option[Int] = None)
    @rejectExtraFields final case class Strict(@fieldName("display_name") name: String)
    final case class InvalidDefault(text: String = "")
    private var fieldChecks = 0
    private val countedValidation = Validation.minLength(1).contramap[String] { value => fieldChecks += 1; value }
    final case class Counted(@validate(countedValidation) text: String)
    final case class Hidden(@transientField @validate(Validation.minLength(1)) text: String = "")
    final case class NestedDefault(hidden: Hidden = Hidden())
    final case class NameCollision(@fieldName("x") first: String, @fieldName("x") second: String)
    final case class AliasCollision(@fieldNameAliases("second") first: String, second: String)

    /** One derivation: the schema and the native converter derived from it. */
    final case class Member(
        @validate(Validation.minLength(2)) name: String,
        @validate(Validation.greaterThan(0)) age: Int,
        tags: List[String],
    ) derives Schema, NativeSchema
    final case class Team(lead: Member, members: List[Member]) derives Schema, NativeSchema

    private def rejected[A](value: js.Any)(using converter: NativeConverter[A]): Either[Throwable, A] =
      try Right(converter.fromNative(value))
      catch case error: Throwable => Left(error)

    given Schema[Pair] = DeriveSchema.gen[Pair]
    given Schema[Constrained] = DeriveSchema.gen[Constrained]
    given Schema[Nested] = DeriveSchema.gen[Nested]
    given Schema[Defaults] = DeriveSchema.gen[Defaults]
    given Schema[Strict] = DeriveSchema.gen[Strict]
    given Schema[Hidden] = DeriveSchema.gen[Hidden]
    given Schema[NestedDefault] = DeriveSchema.gen[NestedDefault]

    def spec = suite("optional native schema bridge")(
      test("constructs records in schema order, independent of JavaScript property order") {
        val codec = NativeSchema(Schema[Pair])
        assertTrue(codec.decode(js.Dynamic.literal(second = "two", first = "one")) == Right(Pair("one", "two")))
      },
      test("rejects null, missing, and incorrectly typed required fields without unsafe casts") {
        val codec = NativeSchema(Schema[Pair])
        val invalid: List[js.Any] = List(null, js.undefined, true, "text", js.Array(), js.Dynamic.literal(),
          js.Dynamic.literal(first = null, second = "two"), js.Dynamic.literal(first = 1, second = "two"),
          js.Dynamic.literal(first = js.Dynamic.literal(), second = "two"))
        assertTrue(invalid.forall(codec.decode(_).isLeft))
      },
      test("validates field annotations") {
        val codec = NativeSchema(Schema[Constrained])
        assertTrue(codec.decode(js.Dynamic.literal(text = "a")).isLeft, codec.decode(js.Dynamic.literal(text = "ab")) == Right(Constrained("ab")))
      },
      test("executes a field validator once") {
        fieldChecks = 0
        val codec = NativeSchema(DeriveSchema.gen[Counted])
        val result = codec.decode(js.Dynamic.literal(text = "ok"))
        assertTrue(result == Right(Counted("ok")), fieldChecks == 1)
      },
      test("runs inherited validation and transform once at their respective types, including Lazy") {
        var validations = 0
        var transforms = 0
        val source = Schema[String].validation(Validation.minLength(1).contramap[String] { value =>
          validations += 1
          value
        })
        val transformed = source.transform[Int](value => { transforms += 1; value.toInt }, _.toString)
          .validation(Validation.greaterThan(10))
        val codec = NativeSchema(Schema.defer(transformed))
        val result = codec.decode("12")
        assertTrue(result == Right(12), validations == 1, transforms == 1)
      },
      test("uses a locally supplied field schema for normalization and domain validation") {
        final case class Input(text: String)
        val schema = {
          given Schema[String] = Schema.Primitive(StandardType.StringType).transform(_.trim, identity)
            .validation(Validation.minLength(1) && Validation.maxLength(4))
          DeriveSchema.gen[Input]
        }
        val codec = NativeSchema(schema)
        assertTrue(codec.decode(js.Dynamic.literal(text = " ok ")) == Right(Input("ok")),
          codec.decode(js.Dynamic.literal(text = " ")).isLeft, codec.decode(js.Dynamic.literal(text = "abcde")).isLeft)
      },
      test("checks finite numbers, integer range, and primitive types") {
        val integers = NativeSchema(Schema[Int])
        val doubles = NativeSchema(Schema[Double])
        val booleans = NativeSchema(Schema[Boolean])
        assertTrue(integers.decode(2) == Right(2), integers.decode(2.5).isLeft,
          integers.decode(2147483648d).isLeft, integers.decode(Double.NaN).isLeft,
          doubles.decode(Double.PositiveInfinity).isLeft, doubles.decode(1.5) == Right(1.5),
          booleans.decode("true").isLeft, booleans.decode(true) == Right(true))
      },
      test("supports nested records, arrays, options, and explicit defaults") {
        val codec = NativeSchema(Schema[Nested])
        val value = Nested(Pair("one", "two"), List("a", "b"), None)
        val raw = js.Dynamic.literal(pair = js.Dynamic.literal(first = "one", second = "two"), names = js.Array("a", "b"))
        assertTrue(codec.decode(raw) == Right(value), codec.decode(codec.encode(value)) == Right(value),
          NativeSchema(Schema[Defaults]).decode(js.Dynamic.literal()) == Right(Defaults()))
      },
      test("respects wire field names and rejects unknown fields when requested") {
        val codec = NativeSchema(Schema[Strict])
        assertTrue(codec.decode(js.Dynamic.literal(display_name = "name")) == Right(Strict("name")),
          codec.decode(js.Dynamic.literal(display_name = "name", extra = true)).isLeft,
          codec.encode(Strict("name")).asInstanceOf[js.Dynamic].display_name.asInstanceOf[String] == "name")
      },
      test("rejects invalid typed defaults instead of bypassing schema validation") {
        val schema = {
          given Schema[String] = Schema.Primitive(StandardType.StringType).validation(Validation.minLength(1))
          DeriveSchema.gen[InvalidDefault]
        }
        for result <- ZIO.attempt(NativeSchema(schema).decode(js.Dynamic.literal())).exit
        yield assertTrue(result.isFailure)
      },
      test("rejects overlapping wire names and aliases before encoding or decoding") {
        for
          names <- ZIO.attempt(NativeSchema(DeriveSchema.gen[NameCollision])).exit
          aliases <- ZIO.attempt(NativeSchema(DeriveSchema.gen[AliasCollision])).exit
        yield assertTrue(
          names.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[NativeSchema.UnsupportedSchema]),
          aliases.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[NativeSchema.UnsupportedSchema]),
        )
      },
      test("validates transient fields inside a defaulted nested record") {
        for result <- ZIO.attempt(NativeSchema(Schema[NestedDefault]).decode(js.Dynamic.literal())).exit
        yield assertTrue(result.isFailure)
      },
      test("explicit converter coexists with a separately derived converter") {
        final case class Message(text: String) derives NativeConverter
        val original = summon[NativeConverter[Message]]
        val codec = NativeSchema(DeriveSchema.gen[Message])
        val bridged = codec.converter
        val value = Message("hello")
        assertTrue(original.fromNative(original.toNative(value)) == value,
          bridged.fromNative(bridged.toNative(value)) == value)
      },
      test("does not serialize native input to JSON text") {
        val codec = NativeSchema(Schema[Pair])
        val original = js.Dynamic.global.JSON.stringify
        val prohibited: js.Function1[js.Any, Nothing] = _ => throw new IllegalStateException("JSON text conversion was used")
        try
          js.Dynamic.global.JSON.updateDynamic("stringify")(prohibited)
          val value = Pair("one", "two")
          assertTrue(codec.decode(codec.encode(value)) == Right(value))
        finally js.Dynamic.global.JSON.updateDynamic("stringify")(original)
      },
      test("keeps transform rejection distinct from runtime defects and unsupported schemas") {
        val rejected = NativeSchema(Schema[String].transformOrFail[String](_ => Left("private detail"), Right(_)))
        val broken = NativeSchema(Schema[String].transform[String](_ => throw new IllegalStateException("private defect"), identity))
        for
          defect <- ZIO.attempt(broken.decode("value")).exit
          unsupported <- ZIO.attempt(NativeSchema(Schema[Map[String, String]])).exit
        yield assertTrue(rejected.decode("value") == Left(NativeSchema.InvalidValue),
          defect.causeOption.flatMap(_.failureOption).exists(_.getMessage == "private defect"),
          unsupported.causeOption.flatMap(_.failureOption).exists(_.isInstanceOf[NativeSchema.UnsupportedSchema]))
      },
      // N15
      test("`derives Schema, NativeSchema` gives a NativeConverter that round-trips") {
        val member = Member("Ada", 36, List("math"))
        val converter = summon[NativeConverter[Member]]
        assertTrue(
          converter.fromNative(converter.toNative(member)) == member,
          summon[NativeSchema[Team]].fromNative(summon[NativeSchema[Team]].toNative(Team(member, List(member)))) == Team(member, List(member)),
        )
      },
      // N16
      test("failed checks throw InvalidInput with one entry per check and its field path, never the input") {
        val input = js.Dynamic.literal(
          lead = js.Dynamic.literal(name = "A", age = 0, tags = js.Array()),
          members = js.Array(js.Dynamic.literal(name = "Bo", age = 1, tags = js.Array()), js.Dynamic.literal(name = "C", age = 2, tags = js.Array())),
        )
        val result = rejected[Team](input)
        assertTrue(
          result == Left(InvalidInput(List(
            ErrorEntry("must have at least 2 characters", Some("lead.name")),
            ErrorEntry("must be greater than 0", Some("lead.age")),
            ErrorEntry("must have at least 2 characters", Some("members[1].name")),
          ))),
          !result.left.exists(error => error.getMessage.contains("\"A\"") || error.getMessage.contains(" C ")),
        )
      },
      // N17
      test("wrong shapes throw a generic InvalidInput, even next to failed checks") {
        val shapes: List[js.Any] = List(null, js.Array(), js.Dynamic.literal(name = "A", age = "old", tags = js.Array()),
          js.Dynamic.literal(name = "Ada", age = 1, tags = js.Array(1)))
        assertTrue(shapes.forall(value => rejected[Member](value) == Left(InvalidInput())))
      },
      // N18
      test("Codec.validate exposes the same rejection without throwing") {
        val codec = NativeSchema(Schema[Constrained])
        assertTrue(
          codec.validate(js.Dynamic.literal(text = "a")) == Left(InvalidInput(List(ErrorEntry("must have at least 2 characters", Some("text"))))),
          codec.validate(js.Dynamic.literal(text = 1)) == Left(InvalidInput()),
          codec.validate(js.Dynamic.literal(text = "ab")) == Right(Constrained("ab")),
        )
      },
    ) @@ TestAspect.sequential
