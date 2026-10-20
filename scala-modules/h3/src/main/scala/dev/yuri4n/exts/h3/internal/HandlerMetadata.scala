package dev.yuri4n.exts.h3.internal

import dev.yuri4n.exts.h3.{ErrorEnvelope, HandlerOptions, Route, SuccessEnvelope, WireModel, WireType}

import scala.scalajs.js

/** What a handler sends on success, for its generated TypeScript declaration. */
private[exts] enum BodyShape:
    /** No body: 204. */
    case Empty

    /** Streamed UTF-8 text. */
    case Text

    /** A converted value; the type is built only when the declaration is written. */
    case Value(wireType: () => WireType)

/** The TypeScript response types of one handler definition. */
private[exts] object RouteTypes:
    // claude 2026-10-20: created: a success type with references to its named models (ADR-0014)
    /** A success type that refers to named models: the TypeScript `expression`, the model names it
      * uses directly, and every model it needs, in dependency order.
      */
    final case class Named(expression: String, uses: List[String], models: List[WireModel])

    // claude 2026-10-20: changed: the inline rendering moved into `declare`, shared with `named`
    /** The body of a successful response, in the handler's success envelope, with every model
      * written out.
      */
    def success(envelope: SuccessEnvelope, body: BodyShape): String = declare(envelope, body, named = false).expression

    // claude 2026-10-20: created: the body type with references to named models (ADR-0014)
    /** The body of a successful response, in the handler's success envelope, with references to
      * its named models.
      */
    def named(envelope: SuccessEnvelope, body: BodyShape): Named = declare(envelope, body, named = true)

    // claude 2026-10-20: created: one rendering of the success body, inline or with model names (ADR-0014)
    private def declare(envelope: SuccessEnvelope, body: BodyShape, named: Boolean): Named =
      def show(wire: WireType): String = if named then wire.renderNamed else wire.render
      def of(expression: String, parts: List[WireType]): Named =
        if named then Named(expression, parts.flatMap(WireType.references).distinct, WireType.modelsOf(parts))
        else Named(expression, Nil, Nil)
      body match
          case BodyShape.Empty           => of("undefined", Nil)
          case BodyShape.Text            => of("string", Nil)
          case BodyShape.Value(wireType) =>
            val wire = wireType()
            envelope match
                case SuccessEnvelope.Data => of(s"{ data: ${show(wire)} }", List(wire))
                case SuccessEnvelope.Raw  =>
                  val (expression, parts) = raw(wire, named, show)
                  of(expression, parts)

    // claude 2026-10-20: changed: looks through named models; gives the rendered parts for the model list
    /** `Raw` sends a value that converts to `null` as 204 without a body, like `Unit`, and `$fetch`
      * returns `undefined` for it. Only the top level changes: JSON text keeps a nested `null`. A
      * named model that holds the top-level `null` is written out, so that its `null` can change.
      * Gives the type and the parts it writes.
      */
    private def raw(wire: WireType, named: Boolean, show: WireType => String): (String, List[WireType]) =
      def reference(model: WireType.Named): Boolean = named && WireType.referenced(model)
      def nullable(wire: WireType): Boolean = wire match
          case WireType.NullValue     => true
          case WireType.OneOf(nested) => nested.exists(nullable)
          case model: WireType.Named  => nullable(model.target)
          case _                      => false
      def members(wire: WireType): List[WireType] = wire match
          case WireType.OneOf(nested)                                         => nested.flatMap(members)
          case model: WireType.Named if !reference(model) || nullable(model.target) => members(model.target)
          case other                                                          => List(other)
      val all = members(wire)
      if !all.contains(WireType.NullValue) || all.contains(WireType.Unknown) then (show(wire), List(wire))
      else
          val parts = all.filterNot(_ == WireType.NullValue).distinctBy(WireType.strip(_, reference))
          ((parts.map(show) :+ "undefined").mkString(" | "), parts)

    /** One error entry as `Rendering` writes it; `debug` appears only in development or debug mode. */
    val entry: String = "{ message: string; path?: string; debug?: { cause: string } }"

    /** The body of an error response, in the handler's error envelope. */
    def errors(envelope: ErrorEnvelope): String = envelope match
        case ErrorEnvelope.List         => s"{ errors: Array<$entry> }"
        case ErrorEnvelope.SingleOrList => s"{ error: $entry } | { errors: Array<$entry> }"

/** The h34s record on a native handler function, under `Symbol.for("h34s.handler")`.
  *
  * `h34s-scala` reads it after linking: `route` and `method` mount the module's default export in
  * Nitro, and `types()` gives the TypeScript of the success and error bodies. Nothing reads it while
  * a request runs.
  */
private[exts] object HandlerMetadata:
    val version: Int = 1

    // claude 2026-10-20: changed: types() also gives the success type with model names and the models (ADR-0014)
    /** Records the response types of a handler definition.
      *
      * `types()` gives `success` and `errors`, with every model written out, and `named`: the
      * success type with references to its named models (`success`), the names it uses directly
      * (`uses`), and the models in dependency order (`models`: `name`, `type`, `uses` and, when
      * known, `scala`, the Scala type's full name). It builds the type once per call.
      */
    def describe(handler: js.Any, options: HandlerOptions, body: BodyShape): js.Any =
      val types: js.Function0[js.Any] = () =>
        val built = body match
            case BodyShape.Value(wireType) =>
              val wire = wireType()
              BodyShape.Value(() => wire)
            case other => other
        val named = RouteTypes.named(options.success, built)
        js.Dynamic.literal(
          success = RouteTypes.success(options.success, built),
          errors = RouteTypes.errors(options.errors),
          named = js.Dynamic.literal(
            success = named.expression,
            uses = js.Array(named.uses*),
            models = js.Array(named.models.map(model)*),
          ),
        )
      attach(handler, js.Dynamic.literal(version = version, types = types))

    // claude 2026-10-20: created: one model of the record (ADR-0014)
    private def model(model: WireModel): js.Any =
      val record = js.Dictionary[js.Any]("name" -> model.name, "type" -> model.definition, "uses" -> js.Array(model.uses*))
      model.scalaType.foreach(name => record("scala") = name)
      record

    /** A new native handler that runs `handler` and carries its types and `route`. */
    def mount(handler: js.Any, route: Route): js.Any =
      val run = handler.asInstanceOf[js.Function1[H3Event, js.Any]]
      val mounted = H3.defineEventHandler(event => run(event))
      val record  = js.Dynamic.literal(version = version, route = route.path)
      route.method.foreach(method => record.method = method.wireName)
      read(handler).foreach(previous => record.types = previous.types)
      attach(mounted, record)

    def read(handler: js.Any): Option[js.Dynamic] =
      if js.typeOf(handler) != "function" then None
      else
          val record = handler.asInstanceOf[WithHandlerMetadata].h34sHandler.getOrElse(null)
          Option.when(Nitro.isObject(record))(record.asInstanceOf[js.Dynamic])

    private def attach(handler: js.Any, record: js.Object): js.Any =
      js.Dynamic.global.Object.defineProperty(
        handler,
        Symbols.handler,
        js.Dynamic.literal(value = record, enumerable = false, configurable = true),
      )
      handler
end HandlerMetadata
