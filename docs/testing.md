# Test your handlers

A handler is a plain function from `Request` to a program. Test it like any other function: build a fake request, run the program with an empty response head, and check the body and the head. You need no h3, no Nitro and no server.

**Status.** `Request.fake` and these patterns are new in `0.2.0-alpha.1`.

## Keep handlers as values

Define the handler function as a value, and pass it to `AppHandler.define` (or `KHandler.define`, `ZHandler.define`) separately. The demo's ZIO handlers do this in `NotesHandler.scala`:

```scala
object NotesHandler:
    val create: Request => ZIO[HandlerEnv & Notes, Throwable, Note] = request =>
      for
        input <- request.body.json[CreateNote]
        note  <- ZIO.serviceWithZIO[Notes](_.create(input.text))
        _     <- Response.status(Status.Created)
      yield note

@JSExportTopLevel("default", "notes-post")
val createNote: EventHandler = AppHandler.define(NotesHandler.create)
```

Put services behind traits, so a test can provide an in-memory implementation.

## Build a fake request

`Request.fake` takes named parameters, all optional:

```scala
Request.fake(
  method = "POST",
  path = "/api/notes",
  headers = Map("content-type" -> "application/json"),
  query = Map("page" -> List("2")),
  routerParams = Map("id" -> "7"),
  cookies = Map("session" -> "abc"),
  body = Some("""{"text":"hello"}"""),
)
```

<!-- claude 2026-10-19: changed: `Request.fake` takes `requireJsonContentType` and follows the handler default (issue #41) -->
`development`, `debug`, `bodyLimit`, `requireJsonContentType` and `runtimeConfig` are also available. Body reads follow the same rules as a real request: the content type, the limit, invalid JSON and schema validation behave the same. Like `HandlerOptions`, `Request.fake` requires a JSON content type by default, so give a fake request with a body that you read with `json[A]` a `content-type` header. Pass `requireJsonContentType = false` to test a handler that turns the option off.

A handler that reads configuration per request with `RuntimeConfig.get(request)` (Kyo) or `ZRuntimeConfig.get(request)` (ZIO) needs the config in the fake request, as JSON text:

```scala
val request = Request.fake(runtimeConfig = Some("""{"greeting":{"salutation":"Hi"}}"""))
```

Without `runtimeConfig`, those reads fail with `ConfigError("h34s Nitro plugin is not installed")`, as they do without the h34s module. Often it is simpler to read configuration in your `AppHandler` and pass values to handlers as services.

## Kyo: run with `Var.runTuple`

Run the program with an empty head and a scope. This is the core's own test (`HandlerBoundarySuite`):

<!-- claude 2026-10-19: changed: the fake request sends a JSON content type (issue #41) -->
```scala
val handler: Request => Greeting < HandlerEffects = request =>
  for
    first <- request.body.json[Input]
    again <- request.body.text()
    _     <- Response.status(Status.Created)
  yield Greeting(s"${first.name} $again")

val request = Request.fake(method = "POST", headers = Map("content-type" -> "application/json"), body = Some("{\"name\":\"Scala\"}"))
val program = Scope.run(Var.runTuple(ResponseHead.empty)(handler(request)))
// program: (ResponseHead, Greeting) < (Async & Abort[Throwable])
```

Run `program` with your Kyo test setup. With munit, the h34s suites turn it into a `Future`:

```scala
import AllowUnsafe.embrace.danger
val result: Future[Result[Throwable, (ResponseHead, Greeting)]] =
  Fiber.Unsafe.init(Abort.run[Throwable](program)).toFuture()
```

Then check `head.status == Some(Status.Created)` and the body. Provide services with `Env.run(fakeService)(handler(request))` before `Var.runTuple`.

`ResponseHead` is what your program set. Handler defaults (`HandlerOptions.defaults`) are added later by the core, so they are not in it.

## ZIO: run with `ZIO.stateful`

Provide the `ZState[ResponseHead]` and a `Scope`. This is the demo's `NotesHandlerSpec`, which uses an in-memory `Notes`:

```scala
private def run[A](program: ZIO[HandlerEnv & Notes, Throwable, A]): Task[(ResponseHead, A, List[String])] =
  for
    created <- Ref.make(List.empty[String])
    result <- ZIO.scoped(ZIO.stateful[Scope](ResponseHead.empty)(
      program.zip(Response.head).provideSomeEnvironment[ZState[ResponseHead] & Scope](_.add[Notes](InMemoryNotes(created))),
    ))
    texts <- created.get
  yield (result._2, result._1, texts)

test("create trims the text through the model schema and answers 201") {
  for (head, note, texts) <- run(NotesHandler.create(post("{\"text\":\"  hello  \"}")))
  yield assertTrue(head.status.contains(Status.Created), note == NoteView(1, "hello"), texts == List("hello"))
}
```

A rejected body fails with the same `InvalidInput` a client would see as a 422:

```scala
test("create rejects invalid input before the service runs") {
  for result <- run(NotesHandler.create(post("{\"text\":\"   \"}"))).either
  yield assertTrue(result == Left(InvalidInput(List(ErrorEntry("must have at least 1 character", Some("text"))))))
}
```

## Test the wire contract

Your clients depend on the JSON shapes, the statuses and the error entries. Pin them in tests:

- **Model shapes.** Convert a value and compare the JSON text with what your TypeScript types expect: `note.toJson` (from `NativeConverter`). A schema type gives the shapes listed in [schemas](schema.md#kyo-wire-shapes).
- **Error entries.** Assert the exact `InvalidInput` entries, as above. They are your 422 contract.
- **Statuses and headers.** Assert `head.status` and `head.headers` from the run. Remember that on failure the core keeps only `OnFailure.Keep` entries.
- **The envelope.** `{"data": ...}`, `{"errors": [...]}` and 204 come from the core and are covered by h34s's own tests. Check them end to end with an HTTP request against a running server, for example in a smoke script.

## Test configuration decoding

Decode JSON text with your config's converter, without Nitro and without `scala.scalajs.js`. The demo's `AppConfigSpec`:

```scala
val runtimeConfig =
  "{\"database\":{\"host\":\"127.0.0.1\",\"port\":5432,\"user\":\"h34s\",\"password\":\"hunter2\",\"name\":\"h34s\"}}"
val config = summon[NativeConverter[AppConfig]].fromJson(runtimeConfig)
assertTrue(config.database.password.reveal == "hunter2", !config.toString.contains("hunter2"))
```

## Test shared resources

Without the Nitro plugin, `Shared` and `ZShared` register nothing. Call `close` at the end of the test to release the resource.

## What h34s tests for you

The h34s suites pin the behavior your handlers rely on. On this branch, on macOS x86_64 with Node 24.19.0: `h3Ext` 36 tests, `kyoH3Ext` 79, `zioH3Ext` 81, `nativeConverterKyoSchemaExt` 25, `nativeConverterZIOSchemaExt` 20, `kyoNuxtHubExt` 15, `zioNuxtHubExt` 14, the demo's `nuxtsvr` 6, and the Node tests of the npm package. They cover request snapshots and body limits, the wire format and envelopes, the failure rule, safe and opaque errors, diagnostics, request finalizers, client disconnects and uninterruptible steps, streaming, `Shared`/`ZShared`, runtime config, storage and the schema bridges, through real h3 1.15.4 events with mocked Node responses. See [compatibility](compatibility.md) for the evidence.
