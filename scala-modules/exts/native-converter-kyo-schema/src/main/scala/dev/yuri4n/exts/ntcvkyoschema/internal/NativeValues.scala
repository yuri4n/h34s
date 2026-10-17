package dev.yuri4n.exts.ntcvkyoschema.internal

import kyo.{Chunk, Codec, Span, Structure}
import kyo.Structure.{PrimitiveKind, Type, Value}
import kyo.schema.{alias, discriminator, rename}

import scala.scalajs.js
import scala.util.control.NoStackTrace

/** Converts between plain JavaScript values and kyo-schema's `Structure.Value` tree.
  *
  * The JavaScript shape is the one kyo-schema-json writes, so `write(Structure.encode(x))` equals
  * `JSON.parse(Json.encode(x))`. The schema's `Structure.Type` is a shape hint. It decides the places
  * where the JSON form differs from the value tree (maps, special `Double` values, scalars that JSON
  * writes as strings) and it makes number reads as strict as kyo-schema-json's reader. Where the hint
  * cannot locate a value (for example a field renamed with the fluent `Schema.rename`), the value is
  * converted without it and kyo-schema's own `Structure.Value` reader decides.
  *
  * Pure apart from reading the input; no JSON text is produced or parsed.
  */
private[ntcvkyoschema] object NativeValues:
    /** The input does not have a shape the schema can read. It never carries input values. */
    final class Malformed extends RuntimeException("Native value does not match the schema") with NoStackTrace

    /** The nesting limit of kyo-schema's codecs. Deeper input is malformed, not a stack overflow. */
    val MaxDepth: Int = Codec.DefaultMaxDepth

    /** Integers above this magnitude are not exact in a JavaScript number. */
    private val MaxSafeInteger: Double = 9007199254740991.0

    // ---- Structure.Value -> JavaScript ----

    def write(value: Value, shape: Option[Type]): js.Any =
      val hint = shape.map(unwrapOptional)
      value match
          case Value.Record(fields) =>
            val target = js.Dictionary.empty[js.Any]
            fields.foreach((name, field) => define(target, name, write(field, hint.flatMap(childShape(_, name)))))
            target
          case Value.VariantCase(name, payload) =>
            val target = js.Dictionary.empty[js.Any]
            define(target, name, write(payload, hint.flatMap(childShape(_, name))))
            target
          case Value.Sequence(elements) =>
            val element = hint.collect { case Type.Collection(_, _, elementType) => elementType }
            js.Array(elements.toSeq.map(write(_, element))*)
          case Value.MapEntries(entries) => writeMap(entries, hint)
          case Value.Str(text)           => text
          case Value.Bool(flag)          => flag
          case Value.Integer(number)     => number.toDouble
          case Value.Decimal(number)     => writeDecimal(number, hint)
          case Value.BigNum(number)      => number.toString
          case Value.Bytes(bytes)        => java.util.Base64.getEncoder.encodeToString(bytes.toArrayUnsafe)
          case Value.Instant(instant)    => instant.toString
          case Value.Duration(duration)  => duration.toString
          case Value.Null                => null

    /** String-keyed maps are objects; any other map is an array of `{ key, value }` records. */
    private def writeMap(entries: Chunk[(Value, Value)], hint: Option[Type]): js.Any =
      val (keyShape, valueShape) = hint match
          case Some(Type.Mapping(_, _, keyType, valueType)) => (Some(keyType), Some(valueType))
          case _                                            => (None, None)
      val stringKeys = keyShape.fold(entries.forall(_._1.isInstanceOf[Value.Str]))(isStringType)
      if stringKeys && entries.forall(_._1.isInstanceOf[Value.Str]) then
          val target = js.Dictionary.empty[js.Any]
          entries.foreach {
            case (Value.Str(key), entry) => define(target, key, write(entry, valueShape))
            case _                       => ()
          }
          target
      else
          js.Array(entries.toSeq.map { (key, entry) =>
            val pair = js.Dictionary.empty[js.Any]
            pair("key") = write(key, keyShape)
            pair("value") = write(entry, valueShape)
            pair: js.Any
          }*)
    end writeMap

    /** kyo-schema-json writes non-finite numbers as the strings `"NaN"`, `"Infinity"`, `"-Infinity"`,
      * and a `Float` with its shortest decimal form.
      */
    private def writeDecimal(number: Double, hint: Option[Type]): js.Any =
      if number.isNaN then "NaN"
      else if number == Double.PositiveInfinity then "Infinity"
      else if number == Double.NegativeInfinity then "-Infinity"
      else
          hint match
              case Some(Type.Primitive(PrimitiveKind.Float, _)) => shortestFloat(number)
              case _                                            => number

    /** The double of the shortest decimal that reads back as the same `Float` (what Ryu writes). */
    private def shortestFloat(number: Double): Double =
      // Zero is exact, and toPrecision would drop the sign of -0.
      if number == 0.0 then number
      else
          val float = number.toFloat
          (1 to 9).iterator
            .map(precision => number.asInstanceOf[js.Dynamic].toPrecision(precision).asInstanceOf[String].toDouble)
            .find(_.toFloat == float)
            .getOrElse(number)

    /** `__proto__` is defined as an own property, like `JSON.parse` does, never as the prototype. */
    private def define(target: js.Dictionary[js.Any], key: String, value: js.Any): Unit =
      if key == "__proto__" then
          js.Object.defineProperty(
            target.asInstanceOf[js.Object],
            key,
            js.Dynamic.literal(value = value, writable = true, enumerable = true, configurable = true)
              .asInstanceOf[js.PropertyDescriptor],
          )
          ()
      else target(key) = value

    // ---- JavaScript -> Structure.Value ----

    /** Reads `native` with the schema's shape as a hint. Throws [[Malformed]] on input that no
      * schema of this shape can read (wrong integer ranges, invalid ISO instants, non-plain objects).
      */
    def read(native: js.Any, shape: Option[Type], depth: Int = 0): Value =
      if depth > MaxDepth then throw Malformed()
      shape match
          case Some(Type.Optional(_, _, inner)) =>
            if isNullish(native) then Value.Null else read(native, Some(inner), depth)
          case Some(Type.Primitive(kind, _)) => readScalar(kind, native, depth)
          case Some(product: Type.Product) if isPlainObject(native) =>
            Value.Record(entries(native).map((key, field) => key -> read(field, fieldShape(product, key), depth + 1)))
          case Some(sum: Type.Sum) if isPlainObject(native) => readVariant(sum, native, depth)
          case Some(Type.Collection(_, _, elementType)) if js.Array.isArray(native) =>
            Value.Sequence(elements(native).map(read(_, Some(elementType), depth + 1)))
          case Some(Type.Mapping(_, _, keyType, valueType)) => readMap(keyType, valueType, native, depth)
          case _                                            => readAny(native, depth)

    /** A value without a usable hint: JSON's own types, nothing else. */
    private def readAny(native: js.Any, depth: Int): Value =
      if depth > MaxDepth then throw Malformed()
      if isNullish(native) then Value.Null
      else
          js.typeOf(native) match
              case "boolean"                            => Value.Bool(native.asInstanceOf[Boolean])
              case "string"                             => Value.Str(native.asInstanceOf[String])
              case "number"                             => readNumber(native.asInstanceOf[Double])
              case "object" if js.Array.isArray(native) => Value.Sequence(elements(native).map(readAny(_, depth + 1)))
              case "object" if isPlainObject(native) =>
                Value.Record(entries(native).map((key, field) => key -> readAny(field, depth + 1)))
              case _ => throw Malformed()

    private def readNumber(number: Double): Value =
      if number.isWhole && math.abs(number) <= MaxSafeInteger then Value.Integer(number.toLong)
      else Value.Decimal(number)

    /** Strict where the JSON reader is strict: integers must be whole and in range, a `Long` must be
      * exact in a JavaScript number, and string-encoded scalars must parse.
      */
    private def readScalar(kind: PrimitiveKind, native: js.Any, depth: Int): Value =
      js.typeOf(native) match
          case "number" =>
            val number = native.asInstanceOf[Double]
            kind match
                case PrimitiveKind.Int   => readInteger(number, Int.MinValue.toDouble, Int.MaxValue.toDouble)
                case PrimitiveKind.Short => readInteger(number, Short.MinValue.toDouble, Short.MaxValue.toDouble)
                case PrimitiveKind.Byte  => readInteger(number, Byte.MinValue.toDouble, Byte.MaxValue.toDouble)
                case PrimitiveKind.Long                         => readInteger(number, -MaxSafeInteger, MaxSafeInteger)
                case PrimitiveKind.Float | PrimitiveKind.Double => Value.Decimal(number)
                case _                                          => readNumber(number)
          case "string" =>
            val text = native.asInstanceOf[String]
            kind match
                case PrimitiveKind.Float | PrimitiveKind.Double => special(text).getOrElse(Value.Str(text))
                case PrimitiveKind.Instant  => parsed(Value.Instant(java.time.Instant.parse(text)))
                case PrimitiveKind.Duration => parsed(Value.Duration(java.time.Duration.parse(text)))
                case PrimitiveKind.Bytes    => parsed(Value.Bytes(Span.fromUnsafe(java.util.Base64.getDecoder.decode(text))))
                case PrimitiveKind.BigInt   => parsed(Value.BigNum(BigDecimal(BigInt(text))))
                case PrimitiveKind.BigDecimal => parsed(Value.BigNum(BigDecimal(text)))
                case _                        => Value.Str(text)
          case _ => readAny(native, depth)

    private def readInteger(number: Double, min: Double, max: Double): Value =
      if number.isWhole && number >= min && number <= max then Value.Integer(number.toLong) else throw Malformed()

    private def special(text: String): Option[Value] =
      text match
          case "NaN"       => Some(Value.Decimal(Double.NaN))
          case "Infinity"  => Some(Value.Decimal(Double.PositiveInfinity))
          case "-Infinity" => Some(Value.Decimal(Double.NegativeInfinity))
          case _           => None

    /** Parse failures of string-encoded scalars are input errors. */
    private def parsed(value: => Value): Value =
      try value
      catch case _: IllegalArgumentException | _: java.time.DateTimeException | _: ArithmeticException => throw Malformed()

    /** External tagging (`{ "Circle": {...} }`, the default) and `@discriminator` tagging. */
    private def readVariant(sum: Type.Sum, native: js.Any, depth: Int): Value =
      val fields = entries(native)
      val external = fields.headOption.filter(_ => fields.size == 1).flatMap { (key, payload) =>
        variant(sum, key).map(found => Value.Record(Chunk(key -> read(payload, Some(found.variantType), depth + 1))))
      }
      external
        .orElse {
          matchable(sum.annotations).collectFirst { case tag: discriminator => tag.tagKey }.flatMap { tagKey =>
            fields.collectFirst { case (`tagKey`, tag) if js.typeOf(tag) == "string" => tag.asInstanceOf[String] }
              .flatMap(variant(sum, _))
              .map(found => read(native, Some(found.variantType), depth))
          }
        }
        .getOrElse(readAny(native, depth))
    end readVariant

    /** A string-keyed object, or the array of `{ key, value }` records used for other keys. */
    private def readMap(keyType: Type, valueType: Type, native: js.Any, depth: Int): Value =
      if isPlainObject(native) then
          Value.Record(entries(native).map((key, entry) => key -> read(entry, Some(valueType), depth + 1)))
      else if js.Array.isArray(native) then
          Value.Sequence(elements(native).map { element =>
            if !isPlainObject(element) then readAny(element, depth + 1)
            else
                Value.Record(entries(element).map { (name, part) =>
                  val partShape = name match
                      case "key"   => Some(keyType)
                      case "value" => Some(valueType)
                      case _       => None
                  name -> read(part, partShape, depth + 2)
                })
          })
      else readAny(native, depth)

    // ---- Shape hints ----

    private def unwrapOptional(shape: Type): Type =
      shape match
          case Type.Optional(_, _, inner) => unwrapOptional(inner)
          case other                      => other

    private def isStringType(shape: Type): Boolean =
      unwrapOptional(shape) match
          case Type.Primitive(PrimitiveKind.String, _) => true
          case _                                       => false

    /** The shape of a named child: a product field, a map value or a sum variant. */
    private def childShape(parent: Type, name: String): Option[Type] =
      parent match
          case product: Type.Product           => fieldShape(product, name)
          case Type.Mapping(_, _, _, valueType) => Some(valueType)
          case sum: Type.Sum                    => variant(sum, name).map(_.variantType)
          case _                                => None

    private def fieldShape(product: Type.Product, wireName: String): Option[Type] =
      product.fields.find(field => wireNames(field.name, field.annotations).contains(wireName)).map(_.fieldType)

    private def variant(sum: Type.Sum, wireName: String): Option[Structure.Variant] =
      sum.variants.find(found => wireNames(found.name, found.annotations).contains(wireName))

    /** The names kyo-schema accepts for a field or variant: `@rename` (or the Scala name) plus `@alias`. */
    private def wireNames(name: String, annotations: Chunk[Any]): Seq[String] =
      val captured = matchable(annotations)
      val primary  = captured.collectFirst { case renamed: rename => renamed.wireName }.getOrElse(name)
      primary +: captured.collect { case aliases: alias => aliases.names }.flatten

    private def matchable(annotations: Chunk[Any]): Seq[Matchable] = annotations.toSeq.map(_.asInstanceOf[Matchable])

    // ---- Native access ----

    private def isNullish(native: js.Any): Boolean = native == null || js.isUndefined(native)

    private val objectPrototype: js.Any = js.Dynamic.global.Object.prototype

    /** Plain objects only (`{}` or `Object.create(null)`), as JSON produces; never class instances. */
    private def isPlainObject(native: js.Any): Boolean =
      !isNullish(native) && js.typeOf(native) == "object" && !js.Array.isArray(native) && {
        val prototype = js.Object.getPrototypeOf(native.asInstanceOf[js.Object])
        prototype == null || js.special.strictEquals(prototype, objectPrototype)
      }

    /** Own enumerable keys in JavaScript order. A key whose value is `undefined` is absent, as in JSON. */
    private def entries(native: js.Any): Chunk[(String, js.Any)] =
      val source = native.asInstanceOf[js.Dynamic]
      Chunk.from(js.Object.keys(native.asInstanceOf[js.Object]).toSeq.flatMap { key =>
        val value = source.selectDynamic(key).asInstanceOf[js.Any]
        if js.isUndefined(value) then None else Some(key -> value)
      })

    private def elements(native: js.Any): Chunk[js.Any] =
      Chunk.from(native.asInstanceOf[js.Array[js.Any]].toSeq)
end NativeValues
