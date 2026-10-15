# Direction: one repository, independent effect integrations

h34s keeps the native h3 bindings and the effect integrations in one repository. Modules should be separately consumable; splitting Git repositories is not needed to achieve that.

| Module responsibility | Dependencies and execution |
| --- | --- |
| Native h3 bindings | Scala.js and h3 facades; no mandatory ZIO or Kyo runtime |
| ZIO API | Ordinary ZIO handlers, services, scopes, streams, and the ZIO runtime |
| Optional ZIO Schema bridges | Schema-driven native conversion and request validation; no schema requirement in the NativeConverter-only core |
| Kyo API | Native Kyo handlers, Env services, Scope cleanup, and Kyo runtime execution; see [Kyo guide](kyo.md) |
| Application | Models, database configuration, service layers, connection ownership, and `AppHandler` |
| Nuxt integration | Connect linked Scala.js handlers to Nitro routes |

ZIO defines the primary documented developer experience. It is not a neutral representation of arbitrary effects, and its programs are not automatically translated into native Kyo execution. Share transport contracts and behavior tests between integrations. A shared Kyo implementation can be evaluated later behind explicit interoperability tests; neither consumer should acquire a second runtime by default.

The current source module names are retained. Published artifact names and a release/compatibility policy are still undecided. The current aggregate also contains historical experimental modules; this change does not claim that its packaging is ready for independent publication.

## Application-owned environments

`ZHandler.define` supplies the HTTP request context, scopes request execution, hides the ZIO runtime/JavaScript Promise boundary, and converts the result through `NativeConverter`.

The example's `AppHandler.define` provides `Services.layer` before crossing that boundary. `Services` composes configuration, a scoped Skunk connection, and `NotesLive`. Endpoint programs use `ZIO.serviceWithZIO[Notes]`; they do not construct connections, runtimes, or transport objects.

This first example opens one connection per request and closes it when that request completes or fails. It is not an application-wide pool. Reusing a `ZLayer` value does not memoize it across requests. A pool will require explicit startup/shutdown ownership and request-scoped leases in a subsequent change.

Skunk runs directly in Scala.js/Nitro. Doobie depends on JDBC and would require a JVM backend; that is not the selected application architecture.

The independent Kyo application supplies `Greetings` through `Env.run` in its own `AppHandler`. It depends on `kyoH3Ext` and the native bindings, without ZIO. Its compiler is Scala 3.9.0 for Kyo 1.0.0-RC7; existing ZIO modules remain on Scala 3.5.2. Both use the Scala.js 1.22.0 linker. See [Kyo](kyo.md) for runtime and lifecycle differences.

## Errors and streaming

Schema validation is optional. NativeConverter-only handlers retain their existing entry point and converter ownership. The Notes example defines normalization and validation once in its model schema and opts into `readBodyWithSchema`. The optional native bridge creates a converter only when explicitly requested; it installs no competing default given. See [schema integration](schema.md) for supported forms and the extension points for JSON, YAML, and Kyo schemas.

`SafeError` messages and explicit `ZHandler.failWith` messages are approved response data. Unexpected exceptions and defects produce status 500 with `Internal server error`. In Nuxt development mode, the response also contains `debug.cause` with the original ZIO cause, nested errors, and trace. Production responses omit that field; full diagnostics remain in server logs.

The Nitro request plugin supplies `event.context.h34s.development` from the build-time `import.meta.dev` flag. The library requires boolean `true` and defaults to opaque responses when the integration is absent. Headers and query parameters cannot enable it. See [debugging](debugging.md) for source maps and the Node debugger.

The Web Stream adapter pulls one upstream chunk at a time and awaits scoped cleanup during cancellation. The adapter queue is bounded by upstream chunk size, not a promise that every chunk contains one element. This is not an end-to-end HTTP memory bound: h3's Web Stream sink does not await Node response `drain`, so a slow client can still grow outgoing Node buffers.

`sendStream` supports direct Node HTTP responses: it observes response close, interrupts upstream work, and releases request resources. A failure after headers have been sent destroys the response instead of appending an error document. h3's no-socket local-fetch path is explicitly rejected because it returns the stream before consumption, which cannot preserve the request scope with this API. Use a browser/direct HTTP request for streaming. Other Nitro deployment adapters and local-fetch stream scope transfer need their own implementation and tests.

## Next coherent slices

1. Package the native bindings and ZIO API as independently consumable artifacts, with a version matrix and fresh-consumer test.
2. Automate route discovery and type generation so adding an endpoint does not require a TypeScript mounting file.
3. Extend the Kyo handler API with optional Schema and streaming adapters, preserving independent runtimes and transport contracts.
4. Add an application-scoped Skunk pool with startup/shutdown and stream lease tests.
5. Verify the production Nuxt/Scala.js pipeline, then add the planned Nix and native-packager paths.
