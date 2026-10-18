package dev.yuri4n.exts.h3.internal

import dev.yuri4n.exts.h3.{ErrorEnvelope, HandlerOptions, Route, SuccessEnvelope, WireType}

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
    /** The body of a successful response, in the handler's success envelope. */
    def success(envelope: SuccessEnvelope, body: BodyShape): String = body match
        case BodyShape.Empty           => "undefined"
        case BodyShape.Text            => "string"
        case BodyShape.Value(wireType) =>
          envelope match
              case SuccessEnvelope.Data => s"{ data: ${wireType().render} }"
              case SuccessEnvelope.Raw  => wireType().render

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

    /** Records the response types of a handler definition. */
    def describe(handler: js.Any, options: HandlerOptions, body: BodyShape): js.Any =
      val types: js.Function0[js.Any] = () =>
        js.Dynamic.literal(success = RouteTypes.success(options.success, body), errors = RouteTypes.errors(options.errors))
      attach(handler, js.Dynamic.literal(version = version, types = types))

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
