# ADR-0006: One merged handler environment, and public test helpers

<!-- claude 2026-10-20: created: title and status -->

- **Status:** accepted
- **Date:** 2026-10-20
- **Decided by:** the maintainer, by accepting the recommendation ("just take the recommended decisions", 2026-10-20)
- **Source:** the [spike memo](https://github.com/yuri4n/h34s/blob/341b6f68b53fe051fd93731b149f894e6dbfe63b/docs/design/spike-sendstream-fetch.md), sections 2.7 and 5 (Q8, Q9, Q10) and section 4, gap 4; [issue #51](https://github.com/yuri4n/h34s/issues/51).

## Context

<!-- claude 2026-10-20: created: context -->

[ADR-0004](0004-streams-are-sent.md) and [ADR-0005](0005-fetch-in-handlers.md) give every handler two new per-request services: a transport that `sendStream` writes to (`HandlerTransport`) and a `Fetch` for `$fetch`. Both must reach the handler through its effect type.

The spike measured a Kyo constraint (memo, section 2.7): with two `Env` entries in `HandlerEffects`, the documented wiring `KHandler.define(request => Env.run(config)(program(request)))` stops compiling in Kyo 1.0.0-RC7. One merged entry, `Env[Fetch & HandlerTransport]`, compiles. The name `HandlerTransport` avoids a clash with `kyo.Exchange` under `import kyo.*`.

On the ZIO side, the prototype has `HandlerEnv = ZState[ResponseHead] & Scope & HandlerTransport`, and the proposed ZIO `$fetch` adds a `ZFetch` service to `HandlerEnv` (memo, Q8).

Tests feel the change. Today `docs/testing.md` builds the handler environment by hand. With the prototype, a direct call of a handler function must also provide `HandlerTransport.detached` and a `Fetch` such as `Fetch.unavailable` (memo, Q10).

## Decision

<!-- claude 2026-10-20: created: decision -->

1. **One merged `Env[Fetch & HandlerTransport]` in `HandlerEffects`** (Q9, option A). Every future per-request service joins the same `Env` intersection.
2. **Public `KHandler.test` and `ZHandler.test`** (Q10, option B). They call a handler with a request, provide a detached transport and a fake or recording `Fetch`, and return the head and the body. Tests never build the handler environment by hand.

## Options considered

<!-- claude 2026-10-20: created: options considered -->

- **Q9 A. One merged `Env[Fetch & HandlerTransport]`.** Chosen, together with the test helper of Q10. It is the shape that compiles with the documented `Env.run` wiring in Kyo RC7, and its type says that a handler can stream.
- **Q9 B. A Kyo `Local` for the transport, and only `Env[Fetch]` in the alias.** Not chosen: the type then does not say that a handler can stream, and `sendStream` fails at run time outside a handler.
- **Q10 A. Document the manual provisioning.** Not chosen. The memo recommends B so that tests never build the environment by hand (its reason for Q9 A); it gives no other reason against A.
- **Q10 B. Public `KHandler.test` and `ZHandler.test`.** Chosen.

## Consequences

<!-- claude 2026-10-20: created: consequences -->

- `HandlerEffects` (Kyo) and `HandlerEnv` (ZIO) change, and so does their text in `docs/kyo.md` and `docs/zio.md` (memo, section 6).
- `docs/testing.md` changes: it shows the test helpers instead of manual provisioning.
- New per-request services do not add `Env` entries; they extend the one intersection, which keeps the documented `Env.run` wiring compiling.

## Follow-ups

<!-- claude 2026-10-20: created: follow-ups -->

- Implement the test helper with the Kyo `$fetch` (memo, section 6, row "Kyo `$fetch`", which includes it) and the ZIO twin.
- Update `docs/testing.md`, `docs/kyo.md`, `docs/zio.md` and the design record in the implementation pull request.
