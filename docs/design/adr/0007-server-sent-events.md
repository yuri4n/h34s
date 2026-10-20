# ADR-0007: Server-sent events through an h34s helper over `sendStream`

<!-- claude 2026-10-20: created: title and status -->

- **Status:** accepted
- **Date:** 2026-10-20
- **Decided by:** the maintainer, by accepting the recommendation ("just take the recommended decisions", 2026-10-20). Decision 4 of issue #51, quoted below, is the maintainer's own (2026-10-19).
- **Source:** the [spike memo](https://github.com/yuri4n/h34s/blob/341b6f68b53fe051fd93731b149f894e6dbfe63b/docs/design/spike-sendstream-fetch.md), section 3, row 17, section 4, gap 7, and section 5, Q7; [issue #51](https://github.com/yuri4n/h34s/issues/51), "Decided by the maintainer", items 4 and 5.

## Context

<!-- claude 2026-10-20: created: context -->

Server-sent events are the preferred server-to-client channel in the feature table (memo, section 3, row 17). The maintainer's target list for h34s includes h3's `createEventStream` (issue #51, decision 5), and decision 4 says that a typed value after a stream is "the last element of the stream (an event ADT as NDJSON/SSE, decoded by a typed `$fetch.stream[E]`)".

h3's `createEventStream` builds a `TransformStream` of SSE frames and sends it with `sendStream`. The spike tested it in-process (memo, Q7): it leaks two unhandled rejections per cancel, and it detects a disconnect only through the request's `close` event, which never fires there.

## Decision

<!-- claude 2026-10-20: created: decision -->

h34s adds its own helper on top of `sendStream` (Q7, option B):

- **Server side:** `Response.sendEvents[S >: StreamEffects](events: Stream[ServerEvent, S]): Sent < HandlerEffects` for Kyo, and its ZIO twin. They set h3's SSE headers and follow the same bridge, scope and cancellation rules as `sendStream` ([ADR-0004](0004-streams-are-sent.md)). `Sent` covers them, because they are a `sendStream`.
- **The stream's effects are bounded as in `sendStream`.** The prototype's Kyo `sendStream` declares `[S >: StreamEffects]`, with `StreamEffects = Async & Abort[Throwable] & Scope`, because the stream runs in its own fiber; the result is `Sent < HandlerEffects` (spike prototype, commit `0196dba`, `kyoh3/Response.scala:64`). The prototype's ZIO `sendStream` takes a `ZStream[Any, E, String]` with `E <: Throwable` and gives `ZIO[ZState[ResponseHead] & HandlerTransport, Throwable, Sent]` (`zioh3/ZHandler.scala:59`). The ZIO twin bounds its stream's environment and error in the same way.
- **Caller side:** `$fetch.events[E]` decodes the frames into typed events ([ADR-0005](0005-fetch-in-handlers.md)).

The memo writes the Kyo signature without the bound (section 3, row 17, and Q7). That leaves `S` unconstrained: a stream with any other effect, such as an application `Env` service, would type-check, and the helper could not run that effect safely (Codex review of PR #58). This record adds the bound from the prototype's `sendStream`.

The memo does not name the ZIO twin. This record does not name it either; the implementation follows the one-name rule of ADR-0004.

## Options considered

<!-- claude 2026-10-20: created: options considered -->

- **A. Wrap h3's `createEventStream`.** Not chosen: in-process it leaks two unhandled rejections per cancel, and it detects a disconnect only through the request's `close` event, which never fires there.
- **B. An h34s helper on top of `sendStream`.** Chosen: it reuses `sendStream`'s cancellation and request-scope rules, and `Sent` already covers it.

## Consequences

<!-- claude 2026-10-20: created: consequences -->

- Server-sent events get the same head timing, failure rule and disconnect behavior as any other sent stream.
- An event stream may use only the effects that `sendStream` accepts. Handle any other effect before the call, for example an application `Env` service in Kyo, or the `ZStream` environment in ZIO.
- A JavaScript route that uses h3's `createEventStream` still reaches a Scala caller as `Streamed`, through its `text/event-stream` content type ([ADR-0005](0005-fetch-in-handlers.md), Q2).
- The caller decodes typed events with `$fetch.events[E]`. Decision 4's `$fetch.stream[E]` for NDJSON is not settled by this record.

## Follow-ups

<!-- claude 2026-10-20: created: follow-ups -->

- Implement the helper after `sendStream` and the Kyo `$fetch` (memo, gap 7; about one implementer day, section 6).
- Add a server-sent events section to `docs/streaming.md` in the same pull request.
