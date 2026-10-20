# ADR-0004: Streams are sent with `Response.sendStream`, not returned

<!-- claude 2026-10-20: created: title and status -->

- **Status:** accepted
- **Date:** 2026-10-20
- **Decided by:** the maintainer. Handoff decisions 1 and 4 of issue #51 are the maintainer's own (2026-10-19). The answers to Q1, Q3, Q8 and Q16 accept the spike memo's recommendations ("just take the recommended decisions", 2026-10-20).
- **Source:** [issue #51](https://github.com/yuri4n/h34s/issues/51), "Decided by the maintainer", items 1 and 4; the [spike memo](https://github.com/yuri4n/h34s/blob/341b6f68b53fe051fd93731b149f894e6dbfe63b/docs/design/spike-sendstream-fetch.md), sections 1, 2.1 to 2.4, 2.7 and 5 (Q1, Q3, Q8, Q16), and section 7, item 3.

## Context

<!-- claude 2026-10-20: created: context -->

In `0.2.0-alpha.1`, a handler returns a `kyo.Stream` or a `ZStream` as its body, and h34s writes the head just before the first chunk. Over Nitro's in-process local fetch, that stream answers with an opaque 500.

On 2026-10-19 the maintainer decided (issue #51):

- **Decision 1.** "Streams are sent, not returned." `sendStream` writes the head and the elements and gives a `Sent` value. The `sendStream` value is the handler's return, and nothing comes after it. A returned `kyo.Stream` or `ZStream` body becomes a compile error, and the stream givens in `ResponseBody` and `ZResponseBody` go away. The bridge, cancellation and request-scope rules stay as they are. The handoff named the ZIO operation `ZHandler.sendStream`.
- **Decision 4.** "A typed value after a stream cannot exist over HTTP; if wanted, it is the last element of the stream (an event ADT as NDJSON/SSE, decoded by a typed `$fetch.stream[E]`)."

The spike prototyped both runtimes and measured the result (memo, sections 1 and 2):

- The "opaque 500" was h34s' own socket guard, not a limit of Nitro or h3. Without the guard, and with three more fixes, Nitro 2.13.1's in-process local fetch is head-first: the caller received the head after 16 to 23 ms, before the first element at about 220 ms.
- Over a real socket, the prototype still writes the head lazily, together with the first chunk.
- A caller that drops a streamed body cancels the callee, and the cancel waits for the callee's stream and request finalizers.
- `@implicitNotFound` on `ResponseBody` and `ZResponseBody` gives a compile error that names `sendStream`.

The spike memo then asked four questions that decide the remaining shape: when the head is written (Q1), what a failure after the head does (Q3), what the operation is called (Q8) and whether a caller's cancel waits for the callee (Q16).

## Decision

<!-- claude 2026-10-20: created: decision -->

1. **Send, do not return.** Both runtimes offer `Response.sendStream(stream)`, which gives `Sent`. A streaming handler returns that `Sent` value. Returning a `kyo.Stream` or a `ZStream` is a compile error whose message names `sendStream` (decision 1).
2. **The head is written at the call, on every transport** (Q1, option A). `sendStream` writes the head as it stands and calls `flushHeaders()` when it is called, with or without a socket.
3. **A stream failure after the head fails the `sendStream` step** (Q3, option B). The core logs the failure and writes nothing more.
4. **One name in both runtimes: `Response.sendStream`** (Q8, option B). This supersedes the handoff's `ZHandler.sendStream` for ZIO.
5. **A caller's cancel waits for the callee's whole request cleanup** (Q16). The cancel resolves only after the callee's stream and request scope have closed.
6. **A typed value after a stream is the stream's last element** (decision 4). See [ADR-0007](0007-server-sent-events.md) for the event helper.

## Options considered

<!-- claude 2026-10-20: created: options considered -->

### Q1: when `sendStream` writes the head over a socket

<!-- claude 2026-10-20: created: options for Q1 -->

- **A. Write the head and call `flushHeaders()` at the call, on every transport.** Chosen. One rule then holds for every transport and every Nitro preset. Socketless presets already give real clients the eager head (memo, section 2.2). Server-sent events and progress clients get the head at once, as decision 3 of #51 asks. The cost: a stream failure before the first element can only end the stream; it cannot change the status.
- **B. Keep the prototype's split.** The head is written lazily before the first chunk when there is a socket, and eagerly without one. Not chosen: two rules for two transports, and over a socket the head is not first (memo, section 2.1; the spike comment on #51 lists this as the first risk, "Q1 fixes it").

### Q3: a stream failure after the head

<!-- claude 2026-10-20: created: options for Q3 -->

- **A. `sendStream` still gives `Sent`, and the core logs the failure.** This is the prototype. Not chosen: a transaction or a finalizer around the stream does not see the failure, so it cannot roll back (the memo's reason for B, read the other way).
- **B. `sendStream` fails with the stream's error; the core logs it and writes nothing more.** Chosen. A transaction or a `Scope` finalizer around the stream then sees the failure and can roll back, as the disconnect guidance already describes for interrupted work. It needs one test per runtime.

### Q8: the operation's name

<!-- claude 2026-10-20: created: options for Q8 -->

- **A. Keep the asymmetric names of decision 1:** `Response.sendStream` for Kyo, `ZHandler.sendStream` for ZIO. Not chosen: two names for one concept.
- **B. `Response.sendStream` in both runtimes.** Chosen: one name per concept, next to the other head operations (`status`, `header`, `cookie`). `zioh3.Response` already exists.
- **C. `KHandler.sendStream` and `ZHandler.sendStream`.** Not chosen. The memo prefers B for the same reason (one name, next to the head operations) and gives no other reason against C.

### Q16: whether a caller's cancel waits for the callee's cleanup

<!-- claude 2026-10-20: created: options for Q16 -->

The memo names no alternative option. It asks whether to keep the prototype's behavior, in which a slow callee finalizer delays the caller. It recommends keeping it, because the behavior mirrors the disconnect rule: finalizers first, then settle.

## Consequences

<!-- claude 2026-10-20: created: consequences -->

- **Breaking change.** Handlers that return a stream stop compiling. Both names need a migration row, because `0.1.0-alpha.1` had a `ZHandler.sendStream` with another signature (memo, Q8). The release notes reverse two statements of the `0.2.0-alpha.1` notes (lines 60 and 86 of `.github/release-notes/0.2.0-alpha.1.md`, memo section 6). This is one reason for the release name in [ADR-0002](0002-next-release-name.md).
- **Guidance for authors.** Acquire resources and validate input before you call `sendStream`. After the call, the status is fixed, and a failure can only end the stream (Q1). The test "a failure before the first chunk is rendered with the failure rule" (`HttpStreamSuite`) changes.
- **Rollback is visible.** A failure after the head reaches the program, so transactional work around a stream can roll back (Q3).
- **Recorded rules change.** `sendStream` writes the native response while the program runs, which the HTTP model rules in `AGENTS.md` and the design record forbid today. The spike memo (section 7, item 3) proposes the new wording for each sentence.
- **In-process streams work.** The documented "opaque 500" for a stream under Nitro's local fetch goes away (memo, section 2.3).

## Follow-ups

<!-- claude 2026-10-20: created: follow-ups -->

- In the implementation pull request, not in this record: replace the `AGENTS.md` sentences of the "HTTP model" section ("returns the response body"; "h34s writes the head and body once ... No public API mutates the native response while the program runs") and of "Environment composition and lifecycle" ("until the stream value is returned" becomes "until `sendStream` is called"), with the text proposed in the spike memo, section 7, item 3.
- In the same pull request, update `docs/design/kyo-core.md`: new numbered decision items after the last existing item (items 15 to 26 came with PR #47), sections 4.2, 4.4 and 8, deviation 9.7 (dev. 28 is superseded), and the appendix facts (memo, section 6, row "Design record and `AGENTS.md`").
- Update `docs/streaming.md`, `docs/migration.md`, `docs/kyo.md`, `docs/zio.md` and the website mirror (memo, section 6).
- Comment on issue #51 with a link to this record; keep #51 open until the implementation round lands.
