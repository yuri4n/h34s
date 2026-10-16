# Direction: one repository, independent effect integrations

h34s is a toolbox for adding Scala.js server code to your Nuxt application. Install the native bindings or the effect integration you need. The repository also contains an optional integration demo in `examples/nuxt/` and an independent documentation app in `website/`.

| Module responsibility | Dependencies and execution |
| --- | --- |
| Native h3 bindings | Scala.js and h3 facades; no mandatory ZIO or Kyo runtime |
| ZIO API | Ordinary ZIO handlers, services, scopes, streams, and the ZIO runtime |
| Optional ZIO Schema bridges | Schema-driven native conversion and request validation; no schema requirement in the NativeConverter-only core |
| Kyo API | Native Kyo handlers, Env services, Scope cleanup, and Kyo runtime execution; see [Kyo guide](kyo.md) |
| Your application | Define models, configure databases, compose service layers, and own resources; optionally write an `AppHandler` wrapper |
| npm/Nuxt integration | Invoke Scala linking and install development loading, source maps, and the diagnostics plugin after configuration |
| Your Nuxt routes | Mount each exported Scala.js handler in a route file |

ZIO defines the primary documented developer experience. It is not a neutral representation of arbitrary effects, and its programs are not automatically translated into native Kyo execution. Share transport contracts and behavior tests between integrations. A shared Kyo implementation can be evaluated later behind explicit interoperability tests; neither consumer should acquire a second runtime by default.

Eleven reusable Maven artifacts and the separate npm tooling are published at `0.1.0-alpha.1`. See [releases](releases.md) for coordinates and [npm tooling](npm-tooling.md) for setup. The demo, aggregates, and experimental migration module are excluded from publication. Installing the toolbox does not install the demo's database service or UI.

## Application-owned environments

`ZHandler.define` supplies the HTTP request context, scopes request execution, hides the ZIO runtime/JavaScript Promise boundary, and converts the result through `NativeConverter`.

Define your services and compose their `ZLayer` values in your application. If you want a shared entry point, write your own `AppHandler.define` to provide that environment before calling `ZHandler.define`. `AppHandler` is the demo's naming convention, not a library export. The optional Notes demo shows this with `Services.layer`, database configuration, a scoped Skunk connection, and `NotesLive`. Its endpoints request `Notes` with `ZIO.serviceWithZIO[Notes]`.

This first example opens one connection per request and closes it when that request completes or fails. It is not an application-wide pool. Reusing a `ZLayer` value does not memoize it across requests. A pool will require explicit startup/shutdown ownership and request-scoped leases in a subsequent change.

Skunk runs directly in Scala.js/Nitro. Doobie depends on JDBC and would require a JVM backend; that is not the selected application architecture.

For Kyo, define your services and resolve their `Env` requirements before calling `KHandler.define`. Follow the demo's optional `AppHandler` wrapper to provide `Greetings` with `Env.run`. Choose `kyoH3Ext` and the native bindings for this path. Its compiler is Scala 3.9.0 for Kyo 1.0.0-RC7; the ZIO modules use Scala 3.5.2. Both use the Scala.js 1.22.0 linker. See [Kyo](kyo.md) for runtime and lifecycle differences.

## Errors and streaming

Schema validation is optional. NativeConverter-only handlers retain their existing entry point and converter ownership. The Notes example defines normalization and validation once in its model schema and opts into `readBodyWithSchema`. The optional native bridge creates a converter only when explicitly requested; it installs no competing default given. See [schema integration](schema.md) for supported forms and the extension points for JSON, YAML, and Kyo schemas.

`SafeError` messages and explicit `ZHandler.failWith` messages are approved response data. Unexpected exceptions and defects produce status 500 with `Internal server error`. In Nuxt development mode, the response also contains `debug.cause` with the original ZIO cause, nested errors, and trace. Production responses omit that field; full diagnostics remain in server logs.

The Nitro request plugin supplies `event.context.h34s.development` from the build-time `import.meta.dev` flag. The library requires boolean `true` and defaults to opaque responses when the integration is absent. Headers and query parameters cannot enable it. See [debugging](debugging.md) for source maps and the Node debugger.

The Web Stream adapter pulls one upstream chunk at a time and awaits scoped cleanup during cancellation. The adapter queue is bounded by upstream chunk size, not a promise that every chunk contains one element. This is not an end-to-end HTTP memory bound: h3's Web Stream sink does not await Node response `drain`, so a slow client can still grow outgoing Node buffers.

`sendStream` supports direct Node HTTP responses: it observes response close, interrupts upstream work, and releases request resources. A failure after headers have been sent destroys the response instead of appending an error document. h3's no-socket local-fetch path is explicitly rejected because it returns the stream before consumption, which cannot preserve the request scope with this API. Use a browser/direct HTTP request for streaming. Other Nitro deployment adapters and local-fetch stream scope transfer need their own implementation and tests.

## Next coherent slices

1. Extend the tested consumer profiles for the published libraries and npm tooling.
2. Automate route discovery and type generation so adding an endpoint does not require a TypeScript mounting file.
3. Extend the Kyo handler API with optional Schema and streaming adapters, preserving independent runtimes and transport contracts.
4. Add an application-scoped Skunk pool with startup/shutdown and stream lease tests.
5. Extend the verified production Nuxt/Scala.js pipeline with the planned Nix and native-packager paths.
