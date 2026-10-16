package dev.yuri4n.exts.ntcvzioschema

import org.getshaka.nativeconverter.{NativeConverter, ParseState}
import zio.{Chunk, Unsafe}
import zio.schema.{Schema, StandardType}
import zio.schema.annotation.validate
import zio.schema.validation.Validation

import scala.scalajs.js

/** An explicit, optional bridge. No Schema-derived NativeConverter is installed implicitly. */
object NativeSchema:
    case object InvalidValue

    final class UnsupportedSchema(message: String) extends IllegalArgumentException(message)

    def apply[A](schema: Schema[A]): Codec[A] =
      checkSupported(schema, Nil)
      new Codec(schema)

    final class Codec[A] private[NativeSchema] (schema: Schema[A]):
        /** Expected input failures are values; schema/validator exceptions are not swallowed. */
        def decode(value: js.Any): Either[InvalidValue.type, A] =
          read(schema, value, 0).map(_.asInstanceOf[A])

        def encode(value: A): js.Any = write(schema, value, 0)

        /** Opt in explicitly, preserving existing custom/derived converter ownership. */
        def converter: NativeConverter[A] = new NativeConverter[A]:
            extension (value: A) def toNative: js.Any = encode(value)

            def fromNative(state: ParseState): A = decode(state.json) match
                case Right(value) => value
                case Left(_)      => state.fail("Value does not match schema")

    private type Result = Either[InvalidValue.type, Any]
    private val maxDepth = 128

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

    private def read(schema: Schema[?], value: js.Any, depth: Int): Result =
      if depth > maxDepth then Left(InvalidValue)
      else
        val result: Result = schema match
            case primitive: Schema.Primitive[?] => readPrimitive(primitive.standardType, value)
            case record: Schema.Record[?] => readRecord(record.asInstanceOf[Schema.Record[Any]], value, depth)
            case optional: Schema.Optional[?] =>
              if value == null || js.isUndefined(value) then Right(None)
              else read(optional.schema, value, depth + 1).map(Some(_))
            case sequence: Schema.Sequence[?, ?, ?] =>
              if !js.Array.isArray(value) then Left(InvalidValue)
              else
                val typed = sequence.asInstanceOf[Schema.Sequence[Any, Any, Any]]
                val values = value.asInstanceOf[js.Array[js.Any]]
                collect(values.toSeq.map(element => read(typed.elementSchema, element, depth + 1)))
                  .map(typed.fromChunk)
            case transform: Schema.Transform[?, ?, ?] =>
              val typed = transform.asInstanceOf[Schema.Transform[Any, Any, Any]]
              read(typed.schema, value, depth + 1).flatMap(typed.f(_).left.map(_ => InvalidValue))
            case deferred: Schema.Lazy[?] => read(deferred.schema, value, depth + 1)
            case _ => throw new UnsupportedSchema("Unsupported native schema")
        result.flatMap { decoded =>
          if decoded == null then throw new IllegalStateException("Schema returned null")
          if valid(schema, decoded) then Right(decoded) else Left(InvalidValue)
        }

    private def readPrimitive(standardType: StandardType[?], value: js.Any): Result =
      standardType match
          case StandardType.StringType if js.typeOf(value) == "string" => Right(value.asInstanceOf[String])
          case StandardType.BoolType if js.typeOf(value) == "boolean" => Right(value.asInstanceOf[Boolean])
          case StandardType.IntType if js.typeOf(value) == "number" =>
            val number = value.asInstanceOf[Double]
            if number >= Int.MinValue && number <= Int.MaxValue && number == math.floor(number) then Right(number.toInt)
            else Left(InvalidValue)
          case StandardType.DoubleType if js.typeOf(value) == "number" =>
            val number = value.asInstanceOf[Double]
            if number.isNaN || number.isInfinity then Left(InvalidValue) else Right(number)
          case _ => Left(InvalidValue)

    private def readRecord(record: Schema.Record[Any], value: js.Any, depth: Int): Result =
      if value == null || js.typeOf(value) != "object" || js.Array.isArray(value) then Left(InvalidValue)
      else
        val objectValue = value.asInstanceOf[js.Object]
        val prototype = js.Object.getPrototypeOf(objectValue)
        if prototype != null && prototype != js.Dynamic.global.Object.prototype then Left(InvalidValue)
        else
          val keys = js.Object.keys(objectValue).toSeq.toSet
          val known = record.fields.flatMap(_.nameAndAliases).toSet
          if record.rejectExtraFields && !keys.subsetOf(known) then Left(InvalidValue)
          else
            val fields = record.fields.map { field =>
              val present = field.nameAndAliases.intersect(keys)
              val useDefault = field.transient || (present.isEmpty && field.optional)
              val decoded: Result =
                if present.size > 1 then Left(InvalidValue)
                else if useDefault then
                  // Defaults are already typed values. Validate through the encoding
                  // direction without executing decode transforms a second time.
                  val default = field.defaultValue.getOrElse(throw new IllegalArgumentException("Schema field has no default"))
                  write(field.schema, default, depth + 1)
                  Right(default)
                else
                  val raw = present.headOption.fold[js.Any](js.undefined)(name => value.asInstanceOf[js.Dynamic].selectDynamic(name))
                  read(field.schema, raw, depth + 1)
              decoded.flatMap { result =>
                if field.validation.asInstanceOf[Validation[Any]].validate(result).isRight then Right(result)
                else if useDefault then throw new IllegalArgumentException("Invalid schema field default")
                else Left(InvalidValue)
              }
            }
            collect(fields).map { values =>
              Unsafe.unsafe { implicit unsafe =>
                record.construct(values).fold(
                  _ => throw new IllegalStateException("Schema record construction failed"),
                  identity,
                )
              }
            }

    private def collect(values: Iterable[Result]): Either[InvalidValue.type, Chunk[Any]] =
      values.foldLeft[Either[InvalidValue.type, Chunk[Any]]](Right(Chunk.empty)) { (acc, value) =>
        for elements <- acc; element <- value yield elements :+ element
      }

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
