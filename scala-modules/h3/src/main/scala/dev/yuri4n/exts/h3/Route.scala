package dev.yuri4n.exts.h3

/** An HTTP method that a route can match. */
enum Method(val wireName: String):
    case Get     extends Method("get")
    case Head    extends Method("head")
    case Post    extends Method("post")
    case Put     extends Method("put")
    case Patch   extends Method("patch")
    case Delete  extends Method("delete")
    case Options extends Method("options")

/** Where Nitro mounts a handler: a path in Nitro's route syntax and, optionally, one method.
  *
  * Paths start with `/` and use Nitro's syntax: `/api/notes`, a parameter in `/api/notes/:id`, and
  * a last segment `**` that matches the rest of the path. A route without a method answers every
  * method.
  *
  * {{{
  * @JSExportTopLevel("default", "notes-get")
  * val listNotes: EventHandler = AppHandler.define(NotesHandler.list).at(Route.get("/api/notes"))
  * }}}
  */
final case class Route private (path: String, method: Option[Method]):
    override def toString: String = method.fold(s"* $path")(value => s"${value.wireName.toUpperCase} $path")

object Route:
    def apply(method: Method, path: String): Route = new Route(validPath(path), Some(method))

    /** A route for every method. */
    def any(path: String): Route = new Route(validPath(path), None)

    def get(path: String): Route     = Route(Method.Get, path)
    def head(path: String): Route    = Route(Method.Head, path)
    def post(path: String): Route    = Route(Method.Post, path)
    def put(path: String): Route     = Route(Method.Put, path)
    def patch(path: String): Route   = Route(Method.Patch, path)
    def delete(path: String): Route  = Route(Method.Delete, path)
    def options(path: String): Route = Route(Method.Options, path)

    /** Why `path` cannot be a Nitro route, or `None` when it can. */
    def problem(path: String): Option[String] =
      if path == null || path.isEmpty then Some("a route path must not be empty")
      else if !path.startsWith("/") then Some(s"a route path must start with '/': $path")
      else if path.exists(char => char.isWhitespace || char.isControl) then
          Some(s"a route path must not contain spaces or control characters: $path")
      else if path.exists(char => "?#\\".contains(char)) then Some(s"a route path must not contain '?', '#' or '\\': $path")
      else if path.length > 1 && path.split("/", -1).drop(1).exists(_.isEmpty) then
          Some(s"a route path must not contain empty segments: $path")
      else None

    private def validPath(path: String): String =
      problem(path).foreach(message => throw IllegalArgumentException(message))
      path
end Route
