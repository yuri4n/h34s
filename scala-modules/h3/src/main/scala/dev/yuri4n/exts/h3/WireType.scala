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

    // claude 2026-10-20: created: a type with a TypeScript name, for the models file (ADR-0014)
    /** A model with a TypeScript name: `target` is its structure.
      *
      * `h34s-scala` declares each named model once, as `export type Note = { ... }` in
      * `h34s-models.d.ts` next to the route declarations, and the routes refer to it by name. The
      * application imports it with `import type { Note } from '#h34s/models'`. The schema bridges
      * name every record and sum after its Scala type; a hand-written converter can name its own:
      *
      * {{{
      * given NativeConverter[Email] = new NativeConverter[Email] with WireType.Described:
      *   val wireType = WireType.Named("Email", WireType.StringValue)
      *   extension (email: Email) def toNative: js.Any = email.value
      *   def fromNative(state: ParseState): Email = Email(state.json.asInstanceOf[String])
      * }}}
      *
      * `scalaType` is the Scala type's full name, when it is known; error messages show it. Inside
      * the structure of a model, a `Named` with the same name refers to that model (a recursive
      * type). Elsewhere, a `Named` whose target is `Unknown` has nothing to declare: it renders as
      * `unknown` and is not exported. A name that is not a TypeScript identifier, or that h34s
      * uses itself (see `WireType.reservedNames`), is not exported either: its structure is
      * written out where it is used.
      */
    case Named(name: String, target: WireType, scalaType: Option[String] = None)

    /** The TypeScript syntax of this type, with every model written out. */
    def render: String = WireType.render(this, _ => false)

    // claude 2026-10-20: created: the rendering with references to named models (ADR-0014)
    /** The TypeScript syntax of this type, with a reference to each named model, for example
      * `Array<Note>`. `models` gives the declarations of those names.
      */
    def renderNamed: String = WireType.render(this, WireType.refers(Nil))

    // claude 2026-10-20: created: the named models of this type (ADR-0014)
    /** The named models that `renderNamed` refers to, directly or through other models, each once,
      * in dependency order: a model comes after the models it uses (a recursive model refers to
      * itself). Two models with the same name and different definitions are both listed:
      * `h34s-scala` reports them.
      */
    def models: List[WireModel] = WireType.modelsOf(List(this))

    /** Whether this type describes `value`, a native value as it reaches the client (after
      * `JSON.parse(JSON.stringify(...))`). An object must not have keys that the type does not list.
      * Use it in tests of a converter that mixes in `WireType.Described`.
      */
    def describes(value: js.Any): Boolean = WireType.describes(this, value)

/** One field of an object type. An optional field may be absent from the object. */
final case class WireField(name: String, fieldType: WireType, optional: Boolean = false)

// claude 2026-10-20: created: one declaration of the models file (ADR-0014)
/** The declaration of one named model: `export type <name> = <definition>`.
  *
  * `definition` refers to other models by name; `uses` lists the names it refers to directly.
  * `scalaType` is the Scala type's full name, when it is known.
  */
final case class WireModel(name: String, definition: String, scalaType: Option[String], uses: List[String])

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
          case Named(_, target, _)  => describes(target, value)
          case ObjectOf(fields)     =>
            isObject && {
              val present = entries.toMap
              present.keySet.subsetOf(fields.map(_.name).toSet) && fields.forall(field =>
                present.get(field.name).fold(field.optional)(describes(field.fieldType, _)),
              )
            }

    // claude 2026-10-20: changed: renders a Named as its name where `reference` says so, otherwise as its target
    /** The TypeScript syntax of `wire`. A `Named` for which `reference` holds is written as its name. */
    private def render(wire: WireType, reference: Named => Boolean): String = wire match
        case Unknown              => "unknown"
        case NullValue            => "null"
        case StringValue          => "string"
        case NumberValue          => "number"
        case BooleanValue         => "boolean"
        case StringLiteral(value) => quote(value)
        case ArrayOf(element)     => s"Array<${render(element, reference)}>"
        case ObjectOf(Nil)        => "Record<string, never>"
        case ObjectOf(fields)     =>
          fields.map(field => s"${key(field.name)}${if field.optional then "?" else ""}: ${render(field.fieldType, reference)}")
            .mkString("{ ", "; ", " }")
        case RecordOf(value)      => s"Record<string, ${render(value, reference)}>"
        case named: Named         => if reference(named) then named.name else render(named.target, reference)
        case OneOf(members)       =>
          // A named model that is written out counts as its structure.
          def unwrap(member: WireType): WireType = member match
              case named: Named if !reference(named) => unwrap(named.target)
              case other                             => other
          val flat = members.flatMap(member =>
            unwrap(member) match
                case OneOf(nested) => nested
                case _             => List(member),
          ).distinctBy(strip(_, reference))
          if flat.isEmpty then "never"
          else if flat.exists(unwrap(_) == Unknown) then "unknown"
          else flat.map(render(_, reference)).mkString(" | ")

    // ---- Named models ----

    // claude 2026-10-20: created: a type without the named models that are written out (ADR-0014)
    /** `wire` with every `Named` for which `reference` does not hold replaced by its target. Two
      * types with the same result are written the same.
      */
    private[exts] def strip(wire: WireType, reference: Named => Boolean): WireType = wire match
        case ArrayOf(element)   => ArrayOf(strip(element, reference))
        case ObjectOf(fields)   => ObjectOf(fields.map(field => field.copy(fieldType = strip(field.fieldType, reference))))
        case RecordOf(value)    => RecordOf(strip(value, reference))
        case OneOf(members)     => OneOf(members.map(strip(_, reference)))
        case named: Named       => if reference(named) then named else strip(named.target, reference)
        case other              => other

    // claude 2026-10-20: created: names that h34s never exports (ADR-0014)
    /** Names that a model never gets: TypeScript and JavaScript keywords, the built-in types that
      * the declarations use (`Array`, `Record`, `Promise`), and the names that each route
      * declaration declares or imports (`SuccessBody`, `ErrorBody`, `EventHandler`,
      * `EventHandlerRequest`). A model with one of these names is written out where it is used.
      */
    val reservedNames: Set[String] = Set(
      // JavaScript reserved words, also in strict mode
      "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do",
      "else", "enum", "export", "extends", "false", "finally", "for", "function", "if", "implements", "import",
      "in", "instanceof", "interface", "let", "new", "null", "package", "private", "protected", "public",
      "return", "static", "super", "switch", "this", "throw", "true", "try", "typeof", "var", "void", "while",
      "with", "yield",
      // TypeScript type keywords and contextual keywords
      "any", "unknown", "never", "number", "bigint", "boolean", "string", "symbol", "object", "undefined",
      "type", "keyof", "infer", "is", "asserts", "readonly", "unique", "as", "satisfies", "declare",
      "namespace", "module", "abstract", "global", "out", "override", "accessor",
      // names that the generated declarations use
      "Array", "Record", "Promise", "SuccessBody", "ErrorBody", "EventHandler", "EventHandlerRequest",
    )

    // claude 2026-10-20: created: whether a name can be an exported TypeScript type alias (ADR-0014)
    private def exportable(name: String): Boolean = identifier.matches(name) && !reservedNames.contains(name)

    // claude 2026-10-20: created: the reference rule inside the definitions of `defining` (ADR-0014)
    /** Which `Named` are written as their name, inside the definitions of the models `defining`
      * (innermost first; `Nil` at the top level): an exportable name with a structure to declare,
      * or a reference to an enclosing model of the same name.
      */
    private def refers(defining: List[Named]): Named => Boolean = named =>
      exportable(named.name) && (named.target != Unknown || defining.exists(same(_, named)))

    /** Whether `inner` refers to the enclosing model `outer`: the same name, and the same Scala type
      * when both are known.
      */
    private def same(outer: Named, inner: Named): Boolean =
      outer.name == inner.name && (outer.scalaType.isEmpty || inner.scalaType.isEmpty || outer.scalaType == inner.scalaType)

    // claude 2026-10-20: created: the names that a rendering refers to directly (ADR-0014)
    /** The model names that `render(wire, reference)` writes, in order, each once. */
    private[exts] def references(wire: WireType, reference: Named => Boolean): List[String] =
      def walk(wire: WireType): List[String] = wire match
          case ArrayOf(element)  => walk(element)
          case ObjectOf(fields)  => fields.flatMap(field => walk(field.fieldType))
          case RecordOf(value)   => walk(value)
          case OneOf(members)    => members.flatMap(walk)
          case named: Named      => if reference(named) then List(named.name) else walk(named.target)
          case _                 => Nil
      walk(wire).distinct

    // claude 2026-10-20: created: the top-level references of renderNamed (ADR-0014)
    /** The model names that `wire.renderNamed` writes, in order, each once. */
    private[exts] def references(wire: WireType): List[String] = references(wire, refers(Nil))

    // claude 2026-10-20: created: the top-level reference rule of renderNamed (ADR-0014)
    /** Whether `renderNamed` writes `named` as its name. */
    private[exts] def referenced(named: Named): Boolean = refers(Nil)(named)

    // claude 2026-10-20: created: the collector of named models in dependency order (ADR-0014)
    /** The named models of `wires` in dependency order, each `(name, definition)` once. */
    private[exts] def modelsOf(wires: List[WireType]): List[WireModel] =
      // A model is collected once per context: the names (and Scala types) of the models around it.
      type Visit = (Named, List[(String, Option[String])])
      final case class Found(models: Vector[WireModel], visited: Set[Visit])

      // `defining`: the models whose structure encloses `wire`, innermost first.
      def collect(wire: WireType, defining: List[Named], found: Found): Found = wire match
          case ArrayOf(element) => collect(element, defining, found)
          case RecordOf(value)  => collect(value, defining, found)
          case ObjectOf(fields) => fields.foldLeft(found)((state, field) => collect(field.fieldType, defining, state))
          case OneOf(members)   => members.foldLeft(found)((state, member) => collect(member, defining, state))
          case named: Named     =>
            val visit: Visit = (named, defining.map(outer => (outer.name, outer.scalaType)))
            if !refers(defining)(named) then collect(named.target, defining, found)
            else if defining.exists(same(_, named)) || found.visited.contains(visit) then found
            else
                val inside    = named :: defining
                val nested    = collect(named.target, inside, found.copy(visited = found.visited + visit))
                val reference = refers(inside)
                val model     = WireModel(named.name, render(named.target, reference), named.scalaType, references(named.target, reference))
                val known     = nested.models.exists(other => other.name == model.name && other.definition == model.definition)
                if known then nested else nested.copy(models = nested.models :+ model)
          case _ => found
      wires.foldLeft(Found(Vector.empty, Set.empty))((found, wire) => collect(wire, Nil, found)).models.toList

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
