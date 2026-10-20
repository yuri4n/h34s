# ADR-0008: Streaming support limits: document, list tested presets only, check the h3 copy

<!-- claude 2026-10-20: created: title and status -->

- **Status:** accepted
- **Date:** 2026-10-20
- **Decided by:** the maintainer, by accepting the recommendation ("just take the recommended decisions", 2026-10-20)
- **Source:** the [spike memo](https://github.com/yuri4n/h34s/blob/341b6f68b53fe051fd93731b149f894e6dbfe63b/docs/design/spike-sendstream-fetch.md), sections 2.2, 2.4 and 5 (Q12, Q13, Q17); [issue #51](https://github.com/yuri4n/h34s/issues/51), spike comment ("Biggest risks"); [issue #36](https://github.com/yuri4n/h34s/issues/36).

## Context

<!-- claude 2026-10-20: created: context -->

The spike found three limits that the implementation round cannot remove at once:

- **A socketless stream that nobody reads or cancels.** In-process, nothing signals a disconnect: only `ReadableStream.cancel` on the response body reaches the callee (memo, section 2.4). `KHandler.$fetch` callers are safe, because their `Scope` cancels the body. A JavaScript caller, or a socketless preset that drops the body without cancelling it, keeps the callee's program and request scope open (Q12).
- **Presets.** Many Nitro presets (for example Cloudflare, Deno, Bun, Vercel Edge, Netlify and AWS Lambda) serve every request through the socketless local call. With `sendStream` they now stream for real clients, but no disconnect reaches h34s unless the platform cancels the body. That is unverified: no preset was built (memo, section 2.2 and Q13).
- **Two h3 copies.** The app pins h3 1.15.4, and nitropack resolves 1.15.5. The functions the spike cites behave the same in both, but nobody checked which copy a built bundle uses (Q17).

## Decision

<!-- claude 2026-10-20: created: decision -->

1. **Document the unread socketless stream now** (Q12, option A). Track an idle timeout in `HandlerOptions` (option B) in an issue, for later, once a preset test exists.
2. **List only node-server and `nuxt dev` as tested presets** in `docs/compatibility.md` (Q13). Issue #36 stays open for the other presets.
3. **Check the h3 copy in the first `nuxt build` of the implementation round** (Q17). No decision is needed unless the two copies differ.

## Options considered

<!-- claude 2026-10-20: created: options considered -->

- **Q12 A. Document it.** Chosen for now.
- **Q12 B. An idle timeout in `HandlerOptions`.** Not chosen now: the memo ties it to a preset test that does not exist yet, and tracks it as an issue. It gives no other reason.
- **Q12 C. Both.** Not chosen now, for the same reason.
- **Q13 and Q17.** The memo names no alternative options for these two questions. It gives one recommendation each, which this record accepts.

## Consequences

<!-- claude 2026-10-20: created: consequences -->

- `docs/streaming.md` explains which callers can leave a stream open, and that `KHandler.$fetch` callers cannot.
- `docs/compatibility.md` claims streaming only for node-server and `nuxt dev`. Every other preset stays untested, as `AGENTS.md` asks for compatibility entries.
- Until the timeout exists, a stream that nobody reads or cancels on a socketless path keeps the callee's program and request scope open until the body is read or cancelled (memo, section 2.4).

## Follow-ups

<!-- claude 2026-10-20: created: follow-ups -->

- Open an issue for the idle timeout (Q12, option B), linked from issue #36.
- Add the h3-copy check to the first `nuxt build` of the implementation round, and record the result in issue #51.
- Comment on issue #36 that it stays open for the presets other than node-server and `nuxt dev`.
