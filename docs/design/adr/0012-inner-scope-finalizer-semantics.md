# ADR-0012: Finalizer failures in a handler's own scopes follow each runtime's rules

<!-- claude 2026-10-20: created: title and status -->

- **Status:** accepted
- **Date:** 2026-10-20
- **Decided by:** the orchestrating session, under the maintainer's rule of 2026-10-20: "go for the best cost-effective solution and open an issue if the solution seems inadequate long-term". The issue lists two options and recommends none.
- **Source:** [issue #50](https://github.com/yuri4n/h34s/issues/50), "Decide whether finalizer failures inside a handler's own scope follow the #40 rule in ZIO", and its comment of 2026-10-20; the [PR #49 review thread](https://github.com/yuri4n/h34s/pull/49#discussion_r4316653045); [issue #40](https://github.com/yuri4n/h34s/issues/40) and [PR #49](https://github.com/yuri4n/h34s/pull/49).

## Context

<!-- claude 2026-10-20: created: context -->

On 2026-10-19 the maintainer decided issue #40 ("align them"): the ZIO adapter follows the Kyo core on finalizer failures. A finalizer that fails after the program produced a response is logged, and the response does not change. This record calls that the #40 rule. PR #49 implements it for ZIO. Its log line is `h34s: a ZIO finalizer failed`.

The #40 rule covers the request scope that h34s owns: the finalizers that a handler adds with `ZIO.addFinalizer` or `ZIO.acquireRelease` in its request `Scope`. While #40 was being finished, Codex found a case on PR #49 that the rule does not cover:

- **ZIO.** A release that dies inside the handler's own `ZIO.scoped` block, or inside a scoped layer provided inside the handler, fails the program under ZIO's own `acquireRelease` semantics. The adapter then answers an opaque 500.
- **Kyo.** A nested `Scope.run` with a failing finalizer logs `Scope finalizer failed` and keeps the 200, because Kyo's `Scope.run` logs finalizer failures itself.

PR #49 records this as an accepted difference, with a documenting test in each runtime: "a release that dies inside the handler's own scope follows the runtime's own rules", in `HandlerBoundarySpec` (ZIO) and in `HandlerBoundarySuite` (Kyo). Issue #50 asked whether to keep the difference.

## Decision

<!-- claude 2026-10-20: created: decision -->

Option 1 of issue #50:

1. **Inner-scope finalizer semantics belong to each runtime.** In ZIO, a release that fails inside a handler's own `ZIO.scoped` block or scoped layer fails the program under ZIO's `acquireRelease` rules, and the client gets an opaque 500. In Kyo, a nested `Scope.run` logs the failure and keeps the response.
2. **The #40 rule stays limited to the request scope in ZIO.** The adapter does not inspect the program's `Exit` for finalizer failures.
3. **PR #49 documents the difference** with one test per runtime, and the ZIO guide states it.

## Options considered

<!-- claude 2026-10-20: created: options considered -->

- **1. Keep it as documented: inner-scope semantics belong to each runtime; ZIO users get ZIO's rules inside their own scopes.** Chosen: it needs no adapter change, and application authors control the case themselves. To get the #40 rule for a cleanup step, add it to the request scope with `ZIO.addFinalizer`.
- **2. Make the ZIO adapter cover inner scopes too, so that "ZIO follows Kyo" holds everywhere.** Not chosen: the adapter cannot tell a finalizer failure from any other defect in the program's `Exit` without inspecting causes. "ZIO follows Kyo everywhere" would then be a design change with its own failure modes and its own tests, for a case that application authors control themselves.

## Consequences

<!-- claude 2026-10-20: created: consequences -->

- **"ZIO follows Kyo" holds for the request scope only.** A ZIO handler whose own `ZIO.scoped` block or scoped layer has a dying release answers an opaque 500. A Kyo handler with a failing finalizer in a nested `Scope.run` answers with its value and logs the failure.
- **The ZIO guide must say which scope gives which rule.** A finalizer that fails in the request scope (`ZIO.addFinalizer` or `ZIO.acquireRelease` in the handler's request `Scope`) is logged and keeps the response. A release inside your own `ZIO.scoped` block, or inside a scoped layer that you provide inside the handler, fails the program and answers 500. PR #49 adds this text to `docs/zio.md`, to `docs/kyo.md` and to their website mirrors (`website/content/1.guide/04.zio.md` and `website/content/1.guide/03.kyo.md`).
- **The solution is adequate in the long term,** as long as the guide says which scope gives which rule. No tracker is needed beyond issue #50.

## Follow-ups

<!-- claude 2026-10-20: created: follow-ups -->

- Land PR #49 with its two documenting tests and the guide text.
- When PR #49 is updated or merged, replace its design-record words "the maintainer may open an issue to cover inner scopes" with a link to this record.
- Close issue #50 when PR #49 and this record have landed, with a comment that links both.
