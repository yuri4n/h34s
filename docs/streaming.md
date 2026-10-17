# Streaming and cancellation

Return a stream as the response body, and h34s sends its elements as they are produced. The request scope stays open until the stream ends, so resources the stream needs stay alive. When the client goes away, h34s stops the stream and waits for its cleanup.

**Status.** Kyo and ZIO stream bodies are part of `0.2.0-SNAPSHOT` (not published yet). They replace `ZHandler.sendStream` from `0.1.0-alpha.1`.

## Return a stream

Kyo: return a `kyo.Stream[A, S]`, where `A` has a `NativeConverter` and `S` needs at most `Async & Abort[Throwable] & Scope`:

```scala
val letters: EventHandler = KHandler.define: _ =>
    Response.status(Status.Accepted)
      .andThen(Response.header("content-type", "text/plain"))
      .andThen(Stream.init(Seq("a", "b", "c")))
```

ZIO: return a `ZStream[Any, E, A]`, where `E <: Throwable` (or `Nothing`) and `A` has a `NativeConverter`:

```scala
val letters: EventHandler = ZHandler.define(_ =>
  Response.status(Status.Accepted) *> Response.header("content-type", "text/plain") *> ZIO.succeed(ZStream("a", "b", "c")),
)
```

Both send `abc` with status 202. These snippets come from the stream tests (`HttpStreamSuite`, `HttpStreamSpec`).

## Know what is written

- h34s writes the response head before the first element, or before an empty stream ends.
- Each element is converted with its `NativeConverter` and written as is: no envelope, no separator, no content type. Set `content-type` yourself.
- Over HTTP, string elements are tested. Encode a text format such as NDJSON or Server-Sent Events yourself, one string per element.
- HTTP clients can merge or split writes. Do not rely on one client-side chunk per element.

## Keep resources alive for the transfer

Acquire what the stream needs in the request scope, then return the stream. h34s keeps the scope open until the stream ends, fails or is cancelled:

- Kyo: `Scope.acquireRelease(open)(close).map(source => ...stream over source...)`, or use a stream that manages its own resources.
- ZIO: `ZIO.acquireRelease(open)(close).map(source => ZStream...)` in the handler (the request `Scope` is in `HandlerEnv`), or `ZStream.unwrapScoped(...)` / `ZStream.acquireReleaseWith(...)` for resources the stream owns.

A resource you close before you return the stream is gone. h34s cannot restore it.

## Cancellation

The bridge is pull-driven. One producer fiber owns the stream and advances only when the HTTP side asks for the next chunk. No chunk is computed before it is requested.

When the client disconnects:

1. h34s interrupts the producer.
2. It waits for the stream's finalizers, including asynchronous ones.
3. It closes the request scope and its resources, once.

A request whose response closed before the stream started never starts the stream.

## Failures

| When the stream fails | Result |
| --- | --- |
| Before the first element | The normal [failure rule](kyo.md#fail-safely): an approved `SafeError` response, or an opaque 500 |
| After the first element | The response is already 2xx. h34s destroys the socket, so the client sees an incomplete transfer; the elements already written stay. The server logs the failure |
| An element converter throws, even a `SafeError` | A defect: the stream's scope closes, and the rule above applies |

Clients should treat an interrupted transfer as a failure, even when the status was successful.

## Limits

| Area | Behavior |
| --- | --- |
| Direct Node HTTP responses | Supported and tested with real h3 and mocked Node responses |
| No socket (Nitro's in-process local fetch, for example a server-side `$fetch` to your own route) | Rejected before any stream resource is acquired: an opaque 500. Request streams over real HTTP |
| Back pressure | One chunk at a time from your stream. h3's sink does not wait for Node's `drain`, so a slow client can still grow Node's output buffers. There is no end-to-end memory bound |
| Other Nitro presets (serverless, edge) | Not tested |

## What is tested

Kyo `StreamSuite` and `HttpStreamSuite`, ZIO `StreamSpec` and `HttpStreamSpec`: pull-driven delivery, head before the first chunk, empty streams, order of effectful, concatenated, filtered and mapped streams, finalization exactly once after the end, cancellation waiting for asynchronous finalizers, disconnects releasing stream and request resources once, failures before and after the first chunk, converter defects, and the no-socket rejection. A real client abort against a running Nuxt server has not been tested on this branch.
