# Application and request lifetimes

Every resource has an owner and a lifetime. h34s gives you two scopes:

| Lifetime | Use it for | Kyo | ZIO |
| --- | --- | --- | --- |
| One request | A pooled connection, a transaction, a temporary file, a stream's source | The request `Scope` in `HandlerEffects` | The request `Scope` in `HandlerEnv` |
| The whole application | A connection pool, an HTTP client, a cache handle | `Shared` | `ZShared` |

Choose the shortest lifetime that works. Open request resources inside the handler; open application resources once.

**Status.** `Shared` and `ZShared` are part of `0.2.0-SNAPSHOT` (not published yet).

## Request resources

The core runs every handler in a request scope. Acquire a resource in that scope, and h34s releases it when the request is done:

- Kyo: `Scope.acquireRelease(acquire)(release)` and `Scope.ensure(finalizer)`.
- ZIO: `ZIO.acquireRelease(acquire)(release)`, `ZIO.addFinalizer`, or a scoped `ZLayer` you provide inside the handler.

"Done" means:

- For a value or `Unit` body: after your program finishes, before h34s writes the response. Asynchronous finalizers finish before the response settles.
- For a streamed body: after the stream ends, fails, or is cancelled.
- When the client disconnects: h34s interrupts the program, waits for the finalizers, and writes nothing.

The demo's ZIO handlers take one pooled Skunk session per request this way. `NotesLive.layer` reads the request `Scope` from `HandlerEnv`, so the session returns to the pool when the request ends:

```scala
object NotesLive:
    /** Notes over one pooled session that lives as long as the request scope. */
    val layer: ZLayer[SessionPool & Scope, Throwable, Notes] =
      ZLayer.fromZIO(ZIO.serviceWithZIO[SessionPool](_.session).map(NotesLive(_)))
```

A failing finalizer behaves differently per runtime. Kyo 1.0.0-RC7 logs a failing `Scope` finalizer and keeps the response. A ZIO finalizer that dies turns a value or `Unit` response into a 500. Do work that must affect the response in the program, not in a finalizer.

## Application resources

`Shared` (Kyo) and `ZShared` (ZIO) hold one resource for all requests:

- The first `get` acquires it. Concurrent first calls share one acquisition.
- A failed acquisition is not cached. The next `get` tries again.
- The acquisition runs on its own fiber. A request that is cancelled while it waits does not cancel the acquisition for the others.
- When the acquisition succeeds, h34s registers the resource with the Nitro close hook.
- `close` releases it and waits for its finalizers. A later `get` acquires it again.

Declare a shared resource once, as a member of an `object`. Nothing runs until the first `get`.

Kyo, with any `A < (Async & Scope & Abort[Throwable])` acquisition. The `Scope` here is the resource's own scope, not a request's:

```scala
object Resources:
    val pool: Shared[Pool] = Shared(
      Scope.acquireRelease(openPool)(pool => closePool(pool)),
    )
```

`openPool`, `closePool` and `Pool` stand for your own code. Use it in a handler with `Resources.pool.get`, which is `Pool < (Async & Abort[Throwable])`.

ZIO, with any `ZLayer[Any, E, R]`. This is the demo's (`AppHandler.scala`):

```scala
/** One Skunk pool for all requests: built on first use, released when Nitro closes. */
private val database = ZShared.layer(SessionPool.layer)
```

Then `database.provide(program)` supplies the shared environment to a program, and `database.get` returns the `ZEnvironment`.

Load configuration inside the acquisition with `RuntimeConfig.load` or `ZRuntimeConfig.load`, not `get`. There is no request at startup. See [configuration](configuration.md).

## One copy per linked project

A `Shared` value is a normal Scala value. Every handler that the same sbt project links shares one copy, because Scala.js puts shared code in one internal module. Two Scala.js projects linked separately have two copies, and each opens its own pool.

## When Nitro closes

The h34s Nitro plugin listens to Nitro's `close` hook and releases every registered `Shared` and `ZShared` value. One failing release does not stop the others. In Nitro 2.13:

- The `node-server` preset calls the hook on `SIGTERM` or `SIGINT` (graceful shutdown, 30 seconds by default; see `NITRO_SHUTDOWN_TIMEOUT` and `NITRO_SHUTDOWN_DISABLED`).
- `nuxt dev` calls it when it replaces the development worker, for example after relinking.

These facts come from Nitro's source. h34s tested the release path with a fake plugin record; a real Nitro shutdown is part of the Phase C verification and is not recorded here yet.

Without the h34s plugin (for example in unit tests), nothing is registered. Call `close` yourself in that case.

## What is tested

`SharedSuite` (Kyo) and `ZSharedSpec` (ZIO): one acquisition for concurrent first calls, an interrupted waiter does not cancel the acquisition, failures are not cached, `close` releases once and a later `get` acquires again, and the close hook releases every registered value. Request-scope tests in both runtimes cover finalizers on success, safe failure and defect, asynchronous finalizers, and client disconnects.
