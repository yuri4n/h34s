package dev.yuri4n.exts.ntcvkyoschema.internal

import dev.yuri4n.exts.h3.{WireField, WireType}
import kyo.{Chunk, Schema, Structure}
import kyo.Structure.{PrimitiveKind, Type}
import kyo.schema.{discriminator, rename}

import scala.scalajs.js
import scala.util.control.NonFatal

/** The TypeScript type of what `NativeValues.write` produces for a schema.
  *
  * The type follows the rules of `NativeValues.write` over the schema's `Structure.Type`. The
  * structure does not show everything the encoder does (fluent `rename`, `drop`, `add`, omit
  * policies, other union representations, also in the schemas of nested types), so the type is
  * then checked against the encoder itself: sample values of every shape (each union member,
  * present and absent options, filled and empty collections) are read with the schema and written
  * again, and every result must have the type. A type that fails, or that cannot be checked, is
  * `unknown`. h34s never writes a type that the encoder did not follow for these samples.
  */
private[ntcvkyoschema] object NativeTypes:
    /** Deeper structures (recursive types) end in `unknown`. */
    private val MaxDepth = 16

    /** `roundTrip` reads a native value with the schema (without its checks) and writes it again;
      * `None` when the schema rejects the value.
      */
    def describe(schema: Schema[?], roundTrip: js.Any => Option[js.Any]): WireType =
      val shape     = schema.structure
      val described = of(shape, 0)
      val checked = for
        variant <- 0 until math.max(1, widest(shape, 0))
        filled  <- List(true, false)
      yield sample(shape, variant, filled, 0).flatMap(roundTrip).exists(described.describes)
      if checked.forall(identity) then described else WireType.Unknown

    // ---- The type ----

    // claude 2026-10-20: changed: records and sums carry their Scala name; the variants of a sum do not (ADR-0014)
    /** The type of `shape`. A record or a sum is a `WireType.Named` after its Scala type, unless it
      * is a generic instantiation (`Page[Note]`) or `named` is false: the variants of a sum keep
      * their place inside the sum's type.
      */
    private def of(shape: Type, depth: Int, named: Boolean = true): WireType =
      if depth > MaxDepth then WireType.Unknown
      else
          shape match
              case Type.Primitive(kind, _)            => primitive(kind)
              case Type.Optional(_, _, inner)         => WireType.nullable(of(inner, depth + 1))
              case Type.Collection(_, _, elementType) => WireType.ArrayOf(of(elementType, depth + 1))
              case Type.Mapping(_, _, keyType, valueType) =>
                if isString(keyType) then WireType.RecordOf(of(valueType, depth + 1))
                else
                    WireType.ArrayOf(WireType.ObjectOf(List(
                      WireField("key", of(keyType, depth + 1)),
                      WireField("value", of(valueType, depth + 1)),
                    )))
              case product: Type.Product =>
                model(named, product.name, product.tag, product.typeParams)(record(product, depth))
              case sum: Type.Sum =>
                model(named, sum.name, sum.tag, sum.typeParams) {
                  tag(sum) match
                      case None      =>
                        WireType.OneOf(sum.variants.toList.map(variant =>
                          WireType.ObjectOf(List(
                            WireField(wireName(variant.name, variant.annotations), of(variant.variantType, depth + 1, named = false)),
                          )),
                        ))
                      case Some(key) =>
                        WireType.OneOf(sum.variants.toList.map { variant =>
                          of(variant.variantType, depth + 1, named = false) match
                              case WireType.ObjectOf(fields) =>
                                WireType.ObjectOf(WireField(key, WireType.StringLiteral(wireName(variant.name, variant.annotations))) :: fields)
                              case _ => WireType.Unknown
                        })
                }
              case _ => WireType.Unknown

    // claude 2026-10-20: created: the named model of a record or a sum (ADR-0014)
    /** `structure` named after the Scala type, when `named` holds and the type has no type
      * arguments. Kyo's `name` is the simple name (`Note`); the tag gives the full name
      * (`app.Models$.Note` reads `app.Models.Note`), which only error messages show.
      */
    private def model(named: Boolean, name: String, typeTag: kyo.Tag[Any], typeParams: Chunk[Type])(structure: WireType): WireType =
      if !named || typeParams.nonEmpty || name.isEmpty then structure
      else
          val full = attempt(typeTag.show).map(_.replace("$.", ".").stripSuffix("$")).filter(full => full == name || full.endsWith(s".$name"))
          WireType.Named(name, structure, full)

    /** Optional fields may be absent or `null`; the samples check both. */
    private def record(product: Type.Product, depth: Int): WireType =
      WireType.ObjectOf(product.fields.toList.map { field =>
        val fieldType = field.fieldType
        WireField(wireName(field.name, field.annotations), of(fieldType, depth + 1), optional = isOptional(fieldType))
      })

    /** What `NativeValues.write` makes of each scalar `Structure.Value`. */
    private def primitive(kind: PrimitiveKind): WireType = kind match
        case PrimitiveKind.String | PrimitiveKind.BigInt | PrimitiveKind.BigDecimal | PrimitiveKind.Bytes |
            PrimitiveKind.Instant | PrimitiveKind.Duration =>
          WireType.StringValue
        case PrimitiveKind.Boolean => WireType.BooleanValue
        case PrimitiveKind.Int | PrimitiveKind.Long | PrimitiveKind.Short | PrimitiveKind.Byte => WireType.NumberValue
        case PrimitiveKind.Float | PrimitiveKind.Double =>
          // Non-finite numbers are written as these strings, as kyo-schema-json writes them.
          WireType.OneOf(WireType.NumberValue :: specials.map(WireType.StringLiteral(_)))
        case _ => WireType.Unknown

    private val specials = List("NaN", "Infinity", "-Infinity")

    // ---- Samples for the encoder check ----

    /** A native value of the shape. `variant` picks the member of each union (modulo its size);
      * `filled` decides between present and absent options, filled and empty collections.
      */
    private def sample(shape: Type, variant: Int, filled: Boolean, depth: Int): Option[js.Any] =
      if depth > MaxDepth then None
      else
          shape match
              case Type.Primitive(kind, _)    => scalar(kind, variant)
              case Type.Optional(_, _, inner) =>
                if filled then sample(inner, variant, filled, depth + 1) else Some(null)
              case Type.Collection(_, _, elementType) =>
                if filled then sample(elementType, variant, filled, depth + 1).map(element => js.Array(element)) else Some(js.Array())
              case Type.Mapping(_, _, keyType, valueType) =>
                if !filled then Some(if isString(keyType) then js.Dictionary.empty[js.Any] else js.Array())
                else if isString(keyType) then sample(valueType, variant, filled, depth + 1).map(value => js.Dictionary("key" -> value))
                else
                    for
                      key   <- sample(keyType, variant, filled, depth + 1)
                      value <- sample(valueType, variant, filled, depth + 1)
                    yield js.Array(js.Dictionary("key" -> key, "value" -> value))
              case product: Type.Product => fields(product, variant, filled, depth)
              case sum: Type.Sum if sum.variants.nonEmpty =>
                val chosen = sum.variants(variant % sum.variants.size)
                val name   = wireName(chosen.name, chosen.annotations)
                tag(sum) match
                    case None      => sample(chosen.variantType, variant, filled, depth + 1).map(payload => js.Dictionary(name -> payload))
                    case Some(key) =>
                      sample(chosen.variantType, variant, filled, depth + 1).collect {
                        case payload if isObject(payload) =>
                          val tagged = js.Dictionary[js.Any](key -> name)
                          payload.asInstanceOf[js.Dictionary[js.Any]].foreach((field, value) => tagged(field) = value)
                          tagged
                      }
              case _ => None

    private def fields(product: Type.Product, variant: Int, filled: Boolean, depth: Int): Option[js.Any] =
      product.fields.toList.foldLeft(Option(js.Dictionary.empty[js.Any])) { (target, field) =>
        val fieldType = field.fieldType
        target.flatMap { dictionary =>
          if !filled && isOptional(fieldType) then Some(dictionary)
          else
              sample(fieldType, variant, filled, depth + 1).map { value =>
                dictionary(wireName(field.name, field.annotations)) = value
                dictionary
              }
        }
      }

    private def scalar(kind: PrimitiveKind, variant: Int): Option[js.Any] = kind match
        case PrimitiveKind.String     => Some("h34s")
        case PrimitiveKind.Boolean    => Some(true)
        case PrimitiveKind.Int | PrimitiveKind.Long | PrimitiveKind.Short | PrimitiveKind.Byte => Some(1)
        case PrimitiveKind.Float | PrimitiveKind.Double =>
          // One finite number and each special string, in turn.
          Some((1.5: js.Any) :: specials.map(text => text: js.Any)).map(values => values(variant % values.size))
        case PrimitiveKind.BigInt     => Some("12")
        case PrimitiveKind.BigDecimal => Some("1.5")
        case PrimitiveKind.Bytes      => Some("aDM0cw==")
        case PrimitiveKind.Instant    => Some("2026-01-02T03:04:05Z")
        case PrimitiveKind.Duration   => Some("PT1S")
        case _                        => None

    /** The size of the largest union in the shape: the number of samples that reach every member. */
    private def widest(shape: Type, depth: Int): Int =
      if depth > MaxDepth then 1
      else
          shape match
              case Type.Primitive(PrimitiveKind.Float | PrimitiveKind.Double, _) => 1 + specials.size
              case Type.Optional(_, _, inner)             => widest(inner, depth + 1)
              case Type.Collection(_, _, elementType)     => widest(elementType, depth + 1)
              case Type.Mapping(_, _, keyType, valueType) => math.max(widest(keyType, depth + 1), widest(valueType, depth + 1))
              case product: Type.Product                  => product.fields.toList.map(field => widest(field.fieldType, depth + 1)).maxOption.getOrElse(1)
              case sum: Type.Sum                          =>
                (sum.variants.size :: sum.variants.toList.map(variant => widest(variant.variantType, depth + 1))).max
              case _ => 1

    // ---- Shape helpers (the same rules as NativeValues) ----

    private def tag(sum: Type.Sum): Option[String] =
      matchable(sum.annotations).collectFirst { case tagged: discriminator => tagged.tagKey }

    private def isString(shape: Type): Boolean = shape match
        case Type.Primitive(PrimitiveKind.String, _) => true
        case Type.Optional(_, _, inner)              => isString(inner)
        case _                                       => false

    private def isOptional(shape: Type): Boolean = shape.isInstanceOf[Type.Optional]

    private def isObject(value: js.Any): Boolean = value != null && js.typeOf(value) == "object" && !js.Array.isArray(value)

    /** The `@rename` name, or the Scala name: the name `NativeValues.write` uses. */
    private def wireName(name: String, annotations: Chunk[Any]): String =
      matchable(annotations).collectFirst { case renamed: rename => renamed.wireName }.getOrElse(name)

    private def matchable(annotations: Chunk[Any]): Seq[Matchable] = annotations.toSeq.map(_.asInstanceOf[Matchable])

    /** Runs `step`; any exception means the sample cannot be checked. */
    def attempt[A](step: => A): Option[A] =
      try Option(step)
      catch case NonFatal(_) => None
end NativeTypes
