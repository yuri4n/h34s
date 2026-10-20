package dev.yuri4n.exts.ntcvzioschema

import dev.yuri4n.exts.h3.{ErrorEntry, InvalidInput, Secret, WireField, WireType}
import org.getshaka.nativeconverter.{NativeConverter, ParseState}
import zio.schema.annotation.validate
import zio.schema.validation.{Validation, ValidationError}
import zio.schema.{Schema, StandardType, TypeId}
import zio.{Chunk, Unsafe}

import scala.scalajs.js

// claude 2026-10-20: changed: the Scaladoc names the models (ADR-0014)
/** A `NativeConverter` derived from a ZIO `Schema`. Opt in per type:
  *
  * {{{
  * final case class CreateNote(@validate(Validation.minLength(1)) text: String) derives Schema, NativeSchema
  * }}}
  *
  * Decoding converts the native value directly (no JSON text) and runs the schema's transforms and
  * validations. Invalid input throws `InvalidInput`: h34s body reads answer it with a 422. A value
  * of the wrong shape gives one generic entry; validation failures give one entry per failed check,
  * with the field path (`pair.first`, `names[1]`) and a message built from the check and its
  * bounds, never from the input value. Encoding exceptions and unsupported schemas are defects.
  *
  * No converter is installed implicitly: only types that derive `NativeSchema` (or call
  * `NativeSchema.derived`) get one.
  *
  * The converter also knows the TypeScript type of what it writes (`WireType.Described`), so h34s
  * types the response of a route that returns it: records are objects with the schema's field
  * names (transient fields left out), `Option` is `T | null`, sequences are arrays, and `String`,
  * `Boolean`, `Int` and `Double` are strings, booleans and numbers.
  *
  * Records are named models (`WireType.Named`) after their `TypeId`: `h34s-scala` declares each
  * one once, as `export type NoteView = ...` in `h34s-models.d.ts`, and the application imports it
  * from `#h34s/models`.
  */
trait NativeSchema[A] extends NativeConverter[A] with WireType.Described

/** A ZIO `Schema` for h34s `Secret`: a string on the wire.
  *
  * Import it where you derive a schema that holds a secret, such as a config model:
  * `import dev.yuri4n.exts.ntcvzioschema.secretSchema`. Decoding never puts the value in an error.
  * JSON that you write with this schema (zio-schema-json) contains the plain value, so do not
  * encode a secret into a response or a log.
  */
given secretSchema: Schema[Secret] = Schema[String].transform(Secret(_), _.reveal)

object NativeSchema:
    case object InvalidValue

    final class UnsupportedSchema(message: String) extends IllegalArgumentException(message)

    /** The converter for `derives NativeSchema`. Fails with `UnsupportedSchema` for schema shapes
      * this bridge does not support.
      */
    def derived[A](using schema: Schema[A]): NativeSchema[A] = apply(schema).converter

    /** An explicit codec for one schema. */
    def apply[A](schema: Schema[A]): Codec[A] =
      checkSupported(schema, Nil)
      new Codec(schema)

    final class Codec[A] private[NativeSchema] (schema: Schema[A]):
        /** Expected input failures are values; schema/validator exceptions are not swallowed. */
        def decode(value: js.Any): Either[InvalidValue.type, A] = validate(value).left.map(_ => InvalidValue)

        /** Like `decode`, with the public details of the rejection. */
        def validate(value: js.Any): Either[InvalidInput, A] =
          read(schema, value, 0, Path.root) match
              case Right(decoded)                => Right(decoded.asInstanceOf[A])
              case Left(Rejection.Shape)         => Left(InvalidInput())
              case Left(Rejection.Invalid(list)) => Left(InvalidInput(list.toList))

        def encode(value: A): js.Any = write(schema, value, 0)

        /** The TypeScript type of what `encode` writes. */
        lazy val wireType: WireType = describe(schema, Nil)

        /** This schema as a `NativeConverter`. Rejected input throws `InvalidInput`. */
        def converter: NativeSchema[A] = new NativeSchema[A]:
            def wireType: WireType = Codec.this.wireType

            extension (value: A) def toNative: js.Any = encode(value)

            def fromNative(state: ParseState): A = validate(state.json) match
                case Right(value)   => value
                case Left(rejected) => throw rejected
    end Codec

    /** Why input was rejected. A shape failure hides the details; validation failures are listed. */
    private enum Rejection:
        case Shape
        case Invalid(entries: Vector[ErrorEntry])

    private type Result = Either[Rejection, Any]
    private val maxDepth = 128

    /** A field path for error entries, for example `pair.first` or `names[1]`. */
    private final case class Path(segments: Vector[String]):
        def field(name: String): Path = Path(segments :+ (if segments.isEmpty then name else s".$name"))
        def index(position: Int): Path = Path(segments :+ s"[$position]")
        def render: Option[String]     = Option.when(segments.nonEmpty)(segments.mkString)

    private object Path:
        val root: Path = Path(Vector.empty)

    /** Combines independent results: any shape failure wins, otherwise validation entries add up. */
    private def collect(values: Iterable[Result]): Either[Rejection, Chunk[Any]] =
      values.foldLeft[Either[Rejection, Chunk[Any]]](Right(Chunk.empty)) {
        case (Left(Rejection.Shape), _)                              => Left(Rejection.Shape)
        case (_, Left(Rejection.Shape))                              => Left(Rejection.Shape)
        case (Left(Rejection.Invalid(left)), Left(Rejection.Invalid(right))) => Left(Rejection.Invalid(left ++ right))
        case (Left(invalid), Right(_))                               => Left(invalid)
        case (Right(_), Left(invalid))                               => Left(invalid)
        case (Right(elements), Right(element))                       => Right(elements :+ element)
      }

    private def checkSupported(schema: Schema[?], seen: List[Schema[?]]): Unit =
      if !seen.exists(previous => previous.asInstanceOf[AnyRef] eq schema.asInstanceOf[AnyRef]) then
        val next = schema :: seen
        schema match
            case Schema.Primitive(StandardType.StringType | StandardType.BoolType | StandardType.IntType | StandardType.DoubleType, _) => ()
            case record: Schema.Record[?] =>
              val names = record.fields.flatMap(_.nameAndAliases)
              if names.distinct.size != names.size then
                throw new UnsupportedSchema("Record fields have overlapping wire names or aliases")
              record.fields.foreach(field => checkSupported(field.schema, next))
            case optional: Schema.Optional[?] => checkSupported(optional.schema, next)
            case sequence: Schema.Sequence[?, ?, ?] => checkSupported(sequence.elementSchema, next)
            case transform: Schema.Transform[?, ?, ?] => checkSupported(transform.schema, next)
            case deferred: Schema.Lazy[?] => checkSupported(deferred.schema, next)
            case _ => throw new UnsupportedSchema(s"Unsupported native schema: ${schema.getClass.getSimpleName}")

    // Transform copies the underlying annotations. Apply those at the original
    // type, and only newly added annotations at the transformed type.
    private def validators(schema: Schema[?]): Chunk[Validation[Any]] =
      val annotations = schema match
          case _: Schema.Lazy[?] => Chunk.empty
          case transform: Schema.Transform[?, ?, ?] =>
            transform.annotations.filterNot(transform.schema.annotations.contains)
          case _ => schema.annotations
      annotations.map(_.asInstanceOf[Matchable]).collect {
        case annotation: validate[?] => annotation.validation.asInstanceOf[Validation[Any]]
      }

    private def valid(schema: Schema[?], value: Any): Boolean =
      validators(schema).forall(_.validate(value).isRight)

    /** Runs every validator once and turns failures into entries at `path`. */
    private def check(validations: Iterable[Validation[Any]], value: Any, path: Path): Result =
      val entries = validations.toVector.flatMap(_.validate(value).left.toOption.toVector.flatMap(_.toVector))
        .map(error => ErrorEntry(describe(error), path.render))
      if entries.isEmpty then Right(value) else Left(Rejection.Invalid(entries.distinct))

    /** A public message for a failed check: the rule and its application-authored bounds only. ZIO
      * Schema's own messages quote the input, so they are never used.
      */
    private def describe(error: ValidationError): String = error match
        case ValidationError.MinLength(minimum, _, _)  => s"must have at least ${characters(minimum)}"
        case ValidationError.MaxLength(maximum, _, _)  => s"must have at most ${characters(maximum)}"
        case ValidationError.GreaterThan(_, expected)  => s"must be greater than $expected"
        case ValidationError.LessThan(_, expected)     => s"must be less than $expected"
        case ValidationError.EqualTo(_, expected)      => s"must be equal to $expected"
        case ValidationError.NotEqualTo(_, expected)   => s"must not be equal to $expected"
        case ValidationError.EqualToNone()             => "is required"
        case ValidationError.MissingField(_)           => "is required"
        case ValidationError.RegexMatch(_, _)          => "does not have the expected format"
        case ValidationError.NotRegexMatch(_, _)       => "does not have the expected format"
        case _                                         => "is not valid"

    private def characters(count: Int): String = if count == 1 then "1 character" else s"$count characters"

    private def read(schema: Schema[?], value: js.Any, depth: Int, path: Path): Result =
      if depth > maxDepth then Left(Rejection.Shape)
      else
        val result: Result = schema match
            case primitive: Schema.Primitive[?] => readPrimitive(primitive.standardType, value)
            case record: Schema.Record[?] => readRecord(record.asInstanceOf[Schema.Record[Any]], value, depth, path)
            case optional: Schema.Optional[?] =>
              if value == null || js.isUndefined(value) then Right(None)
              else read(optional.schema, value, depth + 1, path).map(Some(_))
            case sequence: Schema.Sequence[?, ?, ?] =>
              if !js.Array.isArray(value) then Left(Rejection.Shape)
              else
                val typed = sequence.asInstanceOf[Schema.Sequence[Any, Any, Any]]
                val values = value.asInstanceOf[js.Array[js.Any]]
                collect(values.toSeq.zipWithIndex.map((element, position) =>
                  read(typed.elementSchema, element, depth + 1, path.index(position)),
                )).map(typed.fromChunk)
            case transform: Schema.Transform[?, ?, ?] =>
              val typed = transform.asInstanceOf[Schema.Transform[Any, Any, Any]]
              // A transform rejection message is application text that may quote the input: hide it.
              read(typed.schema, value, depth + 1, path).flatMap(typed.f(_).left.map(_ => Rejection.Shape))
            case deferred: Schema.Lazy[?] => read(deferred.schema, value, depth + 1, path)
            case _ => throw new UnsupportedSchema("Unsupported native schema")
        result.flatMap { decoded =>
          if decoded == null then throw new IllegalStateException("Schema returned null")
          check(validators(schema), decoded, path)
        }

    private def readPrimitive(standardType: StandardType[?], value: js.Any): Result =
      standardType match
          case StandardType.StringType if js.typeOf(value) == "string" => Right(value.asInstanceOf[String])
          case StandardType.BoolType if js.typeOf(value) == "boolean" => Right(value.asInstanceOf[Boolean])
          case StandardType.IntType if js.typeOf(value) == "number" =>
            val number = value.asInstanceOf[Double]
            if number >= Int.MinValue && number <= Int.MaxValue && number == math.floor(number) then Right(number.toInt)
            else Left(Rejection.Shape)
          case StandardType.DoubleType if js.typeOf(value) == "number" =>
            val number = value.asInstanceOf[Double]
            if number.isNaN || number.isInfinity then Left(Rejection.Shape) else Right(number)
          case _ => Left(Rejection.Shape)

    private def readRecord(record: Schema.Record[Any], value: js.Any, depth: Int, path: Path): Result =
      if value == null || js.typeOf(value) != "object" || js.Array.isArray(value) then Left(Rejection.Shape)
      else
        val objectValue = value.asInstanceOf[js.Object]
        val prototype = js.Object.getPrototypeOf(objectValue)
        if prototype != null && prototype != js.Dynamic.global.Object.prototype then Left(Rejection.Shape)
        else
          val keys = js.Object.keys(objectValue).toSeq.toSet
          val known = record.fields.flatMap(_.nameAndAliases).toSet
          if record.rejectExtraFields && !keys.subsetOf(known) then Left(Rejection.Shape)
          else
            val fields = record.fields.map { field =>
              val present = field.nameAndAliases.intersect(keys)
              val useDefault = field.transient || (present.isEmpty && field.optional)
              val fieldPath = path.field(field.fieldName)
              val decoded: Result =
                if present.size > 1 then Left(Rejection.Shape)
                else if useDefault then
                  // Defaults are already typed values. Validate through the encoding
                  // direction without executing decode transforms a second time.
                  val default = field.defaultValue.getOrElse(throw new IllegalArgumentException("Schema field has no default"))
                  write(field.schema, default, depth + 1)
                  Right(default)
                else
                  val raw = present.headOption.fold[js.Any](js.undefined)(name => value.asInstanceOf[js.Dynamic].selectDynamic(name))
                  read(field.schema, raw, depth + 1, fieldPath)
              decoded.flatMap { result =>
                val checked = check(List(field.validation.asInstanceOf[Validation[Any]]), result, fieldPath)
                if checked.isLeft && useDefault then throw new IllegalArgumentException("Invalid schema field default")
                checked
              }
            }
            collect(fields).map { values =>
              Unsafe.unsafely {
                record.construct(values).fold(
                  _ => throw new IllegalStateException("Schema record construction failed"),
                  identity,
                )
              }
            }

    // claude 2026-10-20: changed: a record is a WireType.Named after its TypeId; a repeated record refers to its name (ADR-0014)
    /** The TypeScript type of what `write` produces, case by case. A record is a `WireType.Named`
      * after its nominal `TypeId` (`Note`, with the full name for error messages). A schema that
      * refers to itself (through `Schema.Lazy`) is `unknown` where it repeats; a repeated record
      * there refers to its model by name, so `renderNamed` gives `type Tree = { children: Array<Tree> }`.
      *
      * ZIO's `TypeId` has no type arguments, so a generic record (`Page[Note]`) is named `Page`
      * like any other record. `h34s-scala` writes such a model out where it is used when two of
      * its instantiations meet (ADR-0014).
      */
    private def describe(schema: Schema[?], seen: List[Schema[?]]): WireType =
      if seen.exists(previous => previous.asInstanceOf[AnyRef] eq schema.asInstanceOf[AnyRef]) then
        nominal(schema).fold(WireType.Unknown)((name, full) => WireType.Named(name, WireType.Unknown, Some(full)))
      else
        val next = schema :: seen
        schema match
            case primitive: Schema.Primitive[?] =>
              primitive.standardType match
                  case StandardType.StringType                   => WireType.StringValue
                  case StandardType.BoolType                     => WireType.BooleanValue
                  case StandardType.IntType | StandardType.DoubleType => WireType.NumberValue
                  case _                                         => WireType.Unknown
            case record: Schema.Record[?] =>
              val structure = WireType.ObjectOf(record.fields.toList.filterNot(_.transient).map(field =>
                WireField(field.fieldName, describe(field.schema, next)),
              ))
              nominal(record).fold(structure)((name, full) => WireType.Named(name, structure, Some(full)))
            case optional: Schema.Optional[?]         => WireType.nullable(describe(optional.schema, next))
            case sequence: Schema.Sequence[?, ?, ?]   => WireType.ArrayOf(describe(sequence.elementSchema, next))
            case transform: Schema.Transform[?, ?, ?] => describe(transform.schema, next)
            case deferred: Schema.Lazy[?]             => describe(deferred.schema, next)
            case _                                    => WireType.Unknown

    // claude 2026-10-20: created: the simple and full name of a nominal record (ADR-0014)
    /** The simple and the full name of a record with a nominal `TypeId`, also behind `Schema.Lazy`. */
    private def nominal(schema: Schema[?]): Option[(String, String)] = schema match
        case record: Schema.Record[?] =>
          record.id match
              case id: TypeId.Nominal if id.typeName.nonEmpty => Some((id.typeName, id.fullyQualified))
              case _                                          => None
        case deferred: Schema.Lazy[?] => nominal(deferred.schema)
        case _                        => None

    private def write(schema: Schema[?], value: Any, depth: Int): js.Any =
      require(depth <= maxDepth, "Native schema value exceeds maximum depth")
      require(value != null, "Null is not a schema value")
      require(valid(schema, value), "Value does not match schema")
      schema match
          case primitive: Schema.Primitive[?] =>
            val native: js.Any = primitive.standardType match
                case StandardType.StringType => value.asInstanceOf[String]
                case StandardType.BoolType => value.asInstanceOf[Boolean]
                case StandardType.IntType => value.asInstanceOf[Int]
                case StandardType.DoubleType => value.asInstanceOf[Double]
                case _ => throw new UnsupportedSchema("Unsupported native primitive")
            require(readPrimitive(primitive.standardType, native).isRight, "Value does not match schema")
            native
          case record: Schema.Record[?] =>
            val typed = record.asInstanceOf[Schema.Record[Any]]
            val result = js.Dynamic.global.Object.create(null).asInstanceOf[js.Dictionary[js.Any]]
            typed.fields.foreach { field =>
              val fieldValue = field.get(value)
              require(field.validation.asInstanceOf[Validation[Any]].validate(fieldValue).isRight, "Value does not match schema")
              val encoded = write(field.schema, fieldValue, depth + 1)
              if !field.transient then result(field.fieldName) = encoded
            }
            result
          case optional: Schema.Optional[?] =>
            value.asInstanceOf[Option[Any]].fold[js.Any](null)(write(optional.schema, _, depth + 1))
          case sequence: Schema.Sequence[?, ?, ?] =>
            val typed = sequence.asInstanceOf[Schema.Sequence[Any, Any, Any]]
            js.Array(typed.toChunk(value).map(write(typed.elementSchema, _, depth + 1))*)
          case transform: Schema.Transform[?, ?, ?] =>
            val typed = transform.asInstanceOf[Schema.Transform[Any, Any, Any]]
            val original = typed.g(value).fold(_ => throw new IllegalArgumentException("Value does not match schema"), identity)
            write(typed.schema, original, depth + 1)
          case deferred: Schema.Lazy[?] => write(deferred.schema, value, depth + 1)
          case _ => throw new UnsupportedSchema("Unsupported native schema")
end NativeSchema
