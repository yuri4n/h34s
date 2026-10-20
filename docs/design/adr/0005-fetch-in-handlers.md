# ADR-0005: `$fetch` in handlers: Nitro's `event.fetch`, in-process credentials only

<!-- claude 2026-10-20: created: title and status -->

- **Status:** accepted
- **Date:** 2026-10-20
- **Decided by:** the maintainer. Handoff decisions 2 and 3 of issue #51 are the maintainer's own (2026-10-19). The answers to Q2, Q4, Q5, Q6, Q11, Q14 and Q15 accept the spike memo's recommendations ("just take the recommended decisions", 2026-10-20).
- **Source:** [issue #51](https://github.com/yuri4n/h34s/issues/51), "Decided by the maintainer", items 2 and 3, and "Still open for the maintainer", first two bullets; the [spike memo](https://github.com/yuri4n/h34s/blob/341b6f68b53fe051fd93731b149f894e6dbfe63b/docs/design/spike-sendstream-fetch.md), sections 1, 2.1, 2.4 to 2.7 and 5 (Q2, Q4, Q5, Q6, Q11, Q14, Q15).

## Context

<!-- claude 2026-10-20: created: context -->

`0.2.0-alpha.1` removed the server-side `$fetch` (`docs/migration.md`). On 2026-10-19 the maintainer decided to bring it back (issue #51):

- **Decision 2.** "`$fetch` injected by default into every handler (Kyo: Env + `KHandler.$fetch`; ZIO: `HandlerEnv` + `ZHandler.$fetch`) with Nuxt semantics: the app's own routes resolve in-process (Nitro direct call), `event.$fetch` semantics (incoming cookies/headers forwarded), every route reachable (Scala and JS). 'The Nuxt experience, not the h3 experience.'"
- **Decision 3.** "`$fetch` returns an ADT that knows whether the body is streamed": `FetchResponse.Plain(status, headers, text)` with `.as[A]` through `NativeConverter`, and `FetchResponse.Streamed(status, headers, body: Stream[String, Async & Scope])`. It is head-first. The verbs are `raw`, `text`, `as[A]`, and `get` and `post` as sugar. A caller that drops a `Streamed` body must cancel the callee's producer when its `Scope` closes.

The handoff left two questions open: the `Streamed` detection rule, and whether a Scala-to-Scala call goes through Nitro's local call or calls the handler program directly. The spike prototyped the Kyo `$fetch` and measured these facts (memo, section 2):

- Nitro's `event.fetch` forwards every incoming header, except a few hop-by-hop headers and `accept`, to the target, also to an absolute URL on another host: a test delivered the user's `cookie` and `authorization` to `http://127.0.0.1:<port>/...` (section 2.5). Nuxt's `event.$fetch` behaves the same way.
- `event.$fetch` uses ofetch, which throws on 4xx and 5xx and retries a GET once on 5xx, so a callee ran twice in one experiment (section 2.5).
- No in-process response carries `content-length`, so the proposed rule "no `content-length` means streamed" calls every in-process response streamed (section 2.6).
- The callee can reach the caller's whole `event.context` (section 2.5).

## Decision

<!-- claude 2026-10-20: created: decision -->

1. **The primitive is Nitro's `event.fetch`** (Q6, option A), with two additions: h34s prefixes `app.baseURL`, and it passes a context that holds only `_platform` and `waitUntil`. There is no ofetch retry, throw or buffering.
2. **The API** is `KHandler.$fetch` and `ZHandler.$fetch` with the verbs `raw`, `text`, `as`, `get` and `post`. `raw` returns `FetchResponse.Plain` or `FetchResponse.Streamed` (decision 3).
3. **`Streamed` means the `x-h34s-stream` marker or a stream content type** (`text/event-stream`, `application/x-ndjson`), and nothing else (Q2, option B).
4. **A Scala-to-Scala call goes through Nitro's local call** (Q4, option A), so route rules, middleware, plugin hooks and the error handler run. To reuse logic without HTTP semantics, call your service directly.
5. **Incoming cookies and `authorization` are forwarded only to in-process paths** (`/` paths) (Q5, option B). An absolute URL gets only the headers the caller passes. The guides document this difference from Nuxt's `event.$fetch`.
6. **The name stays `$fetch`** (Q11, option A). Its definition carries `@annotation.nowarn("id=E230")`; call sites compile without warnings.
7. **`Streamed.body` carries typed errors, and headers keep repeated values** (Q14): the body type adds `Abort[FetchError]`, and `FetchResponse` gets `headerValues(name)`, as `Request` already has.
8. **The untyped verbs stay raw** (Q15): `$fetch.get[A]` decodes the JSON as it arrives. The typed route (`$fetch(route)`, gap 10 of the memo) removes the callee's envelope, because only the route knows the callee's `HandlerOptions`.

## Options considered

<!-- claude 2026-10-20: created: options considered -->

### Q6: which Nitro primitive `$fetch` wraps

<!-- claude 2026-10-20: created: options for Q6 -->

- **A. `event.fetch`.** Chosen, with the two additions above. It gives a raw `Response`, with no retry, no throw and no buffering. On its own it does not apply `app.baseURL` and it exposes the caller's context, hence the additions.
- **B. `event.$fetch` (ofetch).** Not chosen: it applies `app.baseURL`, but it buffers, throws on 4xx and 5xx, and retries a GET on 5xx, so a callee can run twice.
- **C. `useNitroApp().localCall`.** Not chosen: it gives the exact body type, but it does no header forwarding.

### Q2: which rule decides `Streamed` or `Plain`

<!-- claude 2026-10-20: created: options for Q2 -->

- **A. The `x-h34s-stream` marker only.** Not chosen. B is preferred because it also covers h3's `createEventStream` and matches what Nuxt's own ofetch streams (`text/event-stream`). The memo gives no other reason against A.
- **B. The marker, or a stream content type.** Chosen: it is exact for every h34s callee on both paths.
- **C. B plus "no `content-length`"** (the handoff's proposal). Not chosen: it calls every in-process response streamed (memo, section 2.6).
- **D. In-process, the body type that `nitroApp.localCall` returns; over the network, the headers.** Not chosen now: it removes B's one gap (below) for about one day of work, because h34s would rebuild `fetchWithEvent`'s forwarding over `localCall`. Revisit it when such a route appears.
- **E. A registry, built from PR #15's metadata, of the routes that stream.** Not chosen: it misses JavaScript routes.

### Q4: Nitro's local call or a direct call of the handler program

<!-- claude 2026-10-20: created: options for Q4 -->

- **A. Nitro's local call.** Chosen. It runs the whole Nitro app: route rules, middleware, the `request`, `beforeResponse` and `afterResponse` plugin hooks, the error handler and the router's parameters. Its cost is a mock request and response and a JSON round trip; nobody has measured that overhead.
- **B. A direct in-process call of the handler program.** Not chosen: it is typed and skips serialization, but it bypasses route rules, middleware (authentication included), plugin hooks and the error handler, and it needs a separate `Request` snapshot.

### Q5: forwarding to a route that is not in the app

<!-- claude 2026-10-20: created: options for Q5 -->

- **A. Nuxt's behavior: forward everything to any host.** Not chosen: a user's session would reach a third party by accident.
- **B. Forward the incoming headers only for `/` paths.** Chosen: in-process calls keep the Nuxt experience, and a user's session never reaches a third party by accident.
- **C. An allow-list of hosts.** Not chosen. The memo recommends B and gives no reason against C.

### Q11: the name

<!-- claude 2026-10-20: created: options for Q11 -->

- **A. Keep `$fetch`.** Chosen: Nuxt users recognize it, and the cost (one `nowarn` at the definition) stays inside h34s.
- **B. Rename it to `fetch`.** Not chosen, for the same reason.

### Q14 and Q15: the body type, the headers and the envelope

<!-- claude 2026-10-20: created: options for Q14 and Q15 -->

The memo names no lettered options for these two questions. It describes the shape it would change and recommends a change:

- **Keep decision 3's body type, `Stream[String, Async & Scope]`, and joined headers.** Not chosen: a callee failure in the middle of the stream reaches the caller as a panic, and `FetchResponse.headers` joins repeated values, `set-cookie` included.
- **Let the untyped verbs remove the callee's envelope** (`{ "data": ... }` by default). Not chosen: only the route knows the callee's `HandlerOptions`.

## Consequences

<!-- claude 2026-10-20: created: consequences -->

- **`$fetch` is not exactly Nuxt's `event.$fetch`.** It does not throw or retry: `raw` returns an error status as a plain response, and `text` and `as` fail with a typed `FetchError.Status`. It forwards credentials only in-process. The guides must say both.
- **One known gap of the detection rule.** A JavaScript route that streams with plain `sendStream` and no content type is `Plain`, so `raw` reads it to the end and never returns for an endless stream (Q2). Option D closes the gap if such a route appears.
- **Known cancellation limit.** A caller that is interrupted before the head arrives cannot stop the callee, because node-mock-http takes no abort signal (memo, section 2.4).
- **The overhead of a local call is unmeasured** (Q4).
- **Migration.** `$fetch` returns with a new signature, so `docs/migration.md` says so explicitly (memo, section 6).
- **The handler environment changes**, because every handler gets a `Fetch`: see [ADR-0006](0006-handler-environment-and-test-helpers.md).

## Follow-ups

<!-- claude 2026-10-20: created: follow-ups -->

- Implement the Kyo `$fetch` policies (memo, gap 3) and the ZIO `$fetch` (gap 6; the ZIO side is not started). Mirror `NitroLocalFetchSuite` for ZIO.
- Build the typed `$fetch(route)` on the route registry of PR #15, which merged on 2026-10-20 (gap 10).
- Update the guides, the migration guide and the design record in the implementation pull request.
- Comment on issue #51 that its two open questions (the detection rule and the local call) are answered here.
