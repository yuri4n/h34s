package dev.yuri4n.exts.h3

import scala.scalajs.js

/** The TypeScript type of the native values that a converter writes.
  *
  * h34s writes these types into the `.d.ts` file next to each linked route module, so that Nuxt's
  * typed `$fetch` knows the response body. A type describes what the converter really writes; when
  * h34s cannot know that, the type is `Unknown` (`unknown` in TypeScript), never a guess.
  */
enum WireType:
    /** Nothing is known about the value. */
    case Unknown
    case NullValue
    case StringValue
    case NumberValue
    case BooleanValue
    case StringLiteral(value: String)
    case ArrayOf(element: WireType)
    case ObjectOf(fields: List[WireField])

    /** An object with string keys and values of one type: `Record<string, T>`. */
    case RecordOf(value: WireType)

    /** Any one of the members: `A | B`. */
    case OneOf(members: List[WireType])

    /** The TypeScript syntax of this type. */
    def render: String = WireType.render(this)

    /** Whether this type describes `value`, a native value as it reaches the client (after
      * `JSON.parse(JSON.stringify(...))`). An object must not have keys that the type does not list.
      * Use it in tests of a converter that mixes in `WireType.Described`.
      */
    def describes(value: js.Any): Boolean = WireType.describes(this, value)

/** One field of an object type. An optional field may be absent from the object. */
final case class WireField(name: String, fieldType: WireType, optional: Boolean = false)

object WireType:
    /** A converter that knows the TypeScript type of the values it writes.
      *
      * Mix it into your own `NativeConverter` to type a custom encoding. The h34s schema bridges
      * (`NativeSchema` for Kyo Schema and ZIO Schema) implement it from the schema.
      *
      * {{{
      * given NativeConverter[Email] = new NativeConverter[Email] with WireType.Described:
      *   val wireType = WireType.StringValue
      *   extension (email: Email) def toNative: js.Any = email.value
      *   def fromNative(state: ParseState): Email = ...
      * }}}
      */
    trait Described:
        def wireType: WireType

    /** `T | null`. */
    def nullable(value: WireType): WireType = OneOf(List(value, NullValue))

    private val identifier = "^[A-Za-z_$][A-Za-z0-9_$]*$".r

    private def describes(wire: WireType, value: js.Any): Boolean =
      def isObject = value != null && js.typeOf(value) == "object" && !js.Array.isArray(value)
      def entries =
        val dictionary = value.asInstanceOf[js.Dictionary[js.Any]]
        js.Object.keys(value.asInstanceOf[js.Object]).toList.map(key => key -> dictionary(key))
      wire match
          case Unknown              => true
          case NullValue            => value == null
          case StringValue          => js.typeOf(value) == "string"
          case NumberValue          => js.typeOf(value) == "number"
          case BooleanValue         => js.typeOf(value) == "boolean"
          case StringLiteral(text)  => js.typeOf(value) == "string" && value.asInstanceOf[String] == text
          case ArrayOf(element)     => js.Array.isArray(value) && value.asInstanceOf[js.Array[js.Any]].forall(describes(element, _))
          case RecordOf(entryType)  => isObject && entries.forall((_, entry) => describes(entryType, entry))
          case OneOf(members)       => members.exists(describes(_, value))
          case ObjectOf(fields)     =>
            isObject && {
              val present = entries.toMap
              present.keySet.subsetOf(fields.map(_.name).toSet) && fields.forall(field =>
                present.get(field.name).fold(field.optional)(describes(field.fieldType, _)),
              )
            }

    private def render(wire: WireType): String = wire match
        case Unknown              => "unknown"
        case NullValue            => "null"
        case StringValue          => "string"
        case NumberValue          => "number"
        case BooleanValue         => "boolean"
        case StringLiteral(value) => quote(value)
        case ArrayOf(element)     => s"Array<${render(element)}>"
        case ObjectOf(Nil)        => "Record<string, never>"
        case ObjectOf(fields)     =>
          fields.map(field => s"${key(field.name)}${if field.optional then "?" else ""}: ${render(field.fieldType)}")
            .mkString("{ ", "; ", " }")
        case RecordOf(value)      => s"Record<string, ${render(value)}>"
        case OneOf(members)       =>
          val flat = members.flatMap {
            case OneOf(nested) => nested
            case other         => List(other)
          }.distinct
          if flat.isEmpty then "never"
          else if flat.contains(Unknown) then "unknown"
          else flat.map(render).mkString(" | ")

    private def key(name: String): String = if identifier.matches(name) then name else quote(name)

    /** A JSON string literal, which is also a valid TypeScript string literal. */
    private def quote(text: String): String =
      val builder = new StringBuilder("\"")
      text.foreach {
        case '"'                 => builder.append("\\\"")
        case '\\'                => builder.append("\\\\")
        case '\n'                => builder.append("\\n")
        case '\r'                => builder.append("\\r")
        case '\t'                => builder.append("\\t")
        case char if char < ' ' || char == ' ' || char == ' ' => builder.append(f"\\u${char.toInt}%04x")
        case char                => builder.append(char)
      }
      builder.append('"').toString
end WireType
