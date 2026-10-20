# ADR-0011: A no-content body keeps the 204 rule and the `undefined` type

<!-- claude 2026-10-20: created: title and status -->

- **Status:** accepted
- **Date:** 2026-10-20
- **Decided by:** the orchestrating session, under the maintainer's rule of 2026-10-20: "go for the best cost-effective solution and open an issue if the solution seems inadequate long-term". The issue lists three options and recommends none.
- **Source:** [issue #46](https://github.com/yuri4n/h34s/issues/46), "Decide how a no-content body with a custom success status is typed for `$fetch`", and its comment of 2026-10-20; the [PR #15 review thread](https://github.com/yuri4n/h34s/pull/15#discussion_r4315356057); the [spike memo](https://github.com/yuri4n/h34s/blob/341b6f68b53fe051fd93731b149f894e6dbfe63b/docs/design/spike-sendstream-fetch.md), section 4, gap 10.

## Context

<!-- claude 2026-10-20: created: context -->

A no-content body is a `Unit` body, or a `SuccessEnvelope.Raw` value that converts to `null` or `undefined`. h34s resolves it as `null`, and h3 sends it with `sendNoContent`: 204 with no body, or the status already set if it is not 200 (design record, section 4.6 and deviations 15 and 16). An explicit 200 with a `Unit` body is therefore sent as 204.

PR #15, merged on 2026-10-20, generates `$fetch` types from Scala. They say `undefined` for both kinds of no-content body, because ofetch 1.5.1 returns `undefined` for a 204.

While fixing the Codex findings on PR #15, the fixer found a gap (issue #46). When a handler sets its own success status, for example 201, h3 sends that status with an empty body, and ofetch returns `""`, not `undefined`. The declared type is then wrong for that status. This already applies to `Unit` bodies in `0.2.0-alpha.1`, so PR #15 did not introduce it.

The wire half of the case is tested today: `HttpHandlerSuite`, "a Unit body is a 204 without a body, in a plain h3 app and in a preemptive router", asserts 201 with an empty body for a `Unit` handler that sets `Status.Created`.

## Decision

<!-- claude 2026-10-20: created: decision -->

Option 1 of issue #46:

1. **Keep the 204 rule.** A no-content body answers 204 when the program set no status or set 200. It keeps any other success status that the program set.
2. **Keep the `undefined` type.** The generated `$fetch` type of a no-content body stays `undefined`.
3. **Document the gap.** The wire-format section of the design record and the generated-types guide state that a no-content body sent with another success status (for example 201 on a `Unit` handler) reaches an ofetch client as `""`.
4. **Pin it with a test.** A test pins the `""` result for that case.

## Options considered

<!-- claude 2026-10-20: created: options considered -->

- **1. Keep the 204 rule and the `undefined` type, and document that a no-content body with another success status reaches the client as `""`.** Chosen: it changes neither the wire nor the generated types. Its cost is one documented case in which the declared type is wrong.
- **2. Force 204 whenever the body is no content, whatever status the program set, so that the type stays `undefined`.** Not chosen: it changes the wire for `0.2.0-alpha.1` consumers who set 201 on a `Unit` handler, for a typing convenience. It would also need a migration-guide entry.
- **3. Declare `undefined | ""` for no-content bodies.** Not chosen: it is truthful, but it makes every `Unit` handler's type `undefined | ""` to describe a case that most handlers never produce.

## Consequences

<!-- claude 2026-10-20: created: consequences -->

- **No breaking change.** The wire and the generated types stay as PR #15 left them, and no migration entry is needed.
- **One documented case has a wrong type.** For a no-content body sent with a success status other than 200 and 204, the client receives `""` where the type says `undefined`. Client code that compares the result with `undefined` does not see that case.
- **The solution looks inadequate in the long term.** The type should follow the status. Under the maintainer's rule, issue #46 stays open as the tracker. The typed route registry ([ADR-0005](0005-fetch-in-handlers.md), decision 8; gap 10 of the spike memo, `Route.get[A](path)` and `KHandler.$fetch(route)`) knows each route's `HandlerOptions`, so it can type the no-content case per declared status.

## Follow-ups

<!-- claude 2026-10-20: created: follow-ups -->

- In `docs/design/kyo-core.md`, state the `""` case in section 4.6 or next to deviations 15 and 16, replace the "Still open" item for #46 and link to this record.
- In `docs/routes.md` and its website mirror, `website/content/1.guide/02.routes.md`, state the `""` case next to the `undefined` type.
- Add the test that pins the `""` result of a no-content body sent with 201. The wire half is already pinned by `HttpHandlerSuite`.
- Keep issue #46 open until the typed route registry lands. Then close it, or turn it into the status-aware typing item.
- Comment on issue #46 with a link to this record.
