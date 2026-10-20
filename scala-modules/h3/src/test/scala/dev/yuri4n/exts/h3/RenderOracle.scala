// The oracle of the inline rendering, for the named-model change (ADR-0014). Copied verbatim from
// commit 5f894e9: WireType.render, key and quote
// (scala-modules/h3/src/main/scala/dev/yuri4n/exts/h3/WireType.scala) and RouteTypes.raw
// (scala-modules/h3/src/main/scala/dev/yuri4n/exts/h3/internal/HandlerMetadata.scala). The only
// edits: `private` removed from `render` and `raw`, and `raw` calls this `render` instead of
// `wire.render`. A failing property means the new code is wrong, not the oracle.
package dev.yuri4n.exts.h3

import dev.yuri4n.exts.h3.WireType.*

object RenderOracle:
    private val identifier = "^[A-Za-z_$][A-Za-z0-9_$]*$".r

    def render(wire: WireType): String = wire match
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

    def raw(wire: WireType): String =
      def members(wire: WireType): List[WireType] = wire match
          case WireType.OneOf(nested) => nested.flatMap(members)
          case other                  => List(other)
      val all = members(wire)
      if !all.contains(WireType.NullValue) || all.contains(WireType.Unknown) then render(wire)
      else (all.filterNot(_ == WireType.NullValue).distinct.map(render) :+ "undefined").mkString(" | ")
end RenderOracle
