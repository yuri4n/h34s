# ADR-0001: Delivery process: the orchestrating session merges green pull requests

<!-- claude 2026-10-20: created: title and status -->

- **Status:** accepted
- **Date:** 2026-10-20
- **Decided by:** the orchestrating session, from the maintainer's words quoted under Context ("the user does not want to review PRs", 2026-10-19; "just take the recommended decisions", 2026-10-20). The sources gave no recommendation for this question.
- **Source:** [issue #51](https://github.com/yuri4n/h34s/issues/51), "Still open for the maintainer", process bullet; the [spike memo](https://github.com/yuri4n/h34s/blob/341b6f68b53fe051fd93731b149f894e6dbfe63b/docs/design/spike-sendstream-fetch.md), section 7, item 1; `AGENTS.md`, "Change discipline and delivery workflow".

## Context

<!-- claude 2026-10-20: created: context -->

`AGENTS.md` asks for focused pull requests and says: "Do not merge PRs, force-push, rewrite shared history, or delete branches without explicit authorization." The design record says the same in its first list of decisions: "Nothing is merged, force-pushed or published without the user's explicit approval" (`docs/design/kyo-core.md`, section 1, "Recorded before the refactor").

The maintainer's handoff of 2026-10-19 says that the next implementation round is "pushed straight to main after the full check (the user does not want to review PRs)". Issue #51 and the spike memo (section 7, item 1) pointed out that the two rules conflict, and asked for one rule in `AGENTS.md` before the round starts.

Meanwhile, pull requests waited. The spike memo lists five that were open and waiting for review: #15, #45, #47, #48 and #49. Together with #55, six pull requests with green checks had waited about a day when this decision was made.

On 2026-10-20 the maintainer wrote: "just take the recommended decisions and record the the other options and the why (adrs ?) I want to see the product and then I'll care".

## Decision

<!-- claude 2026-10-20: created: decision -->

1. A pull request stays the unit of change. Its gates are CI, the Codex review bot (`chatgpt-codex-connector[bot]`) and the Vercel preview (`vercel[bot]`).
2. The orchestrating session merges a pull request once CI is green and every bot finding is resolved. It does not wait for the maintainer's review. It squash-merges, with the repository's existing commit title style `<title> (#N)`.
3. The maintainer reviews after the fact, on merged history, and can revert any merge.
4. This pull request updates `AGENTS.md`; a later change to this rule does the same.
5. The maintainer can reinstate gated review with one word. From then on, the orchestrating session waits for the maintainer's approval again.
6. Unchanged: release tags and the Vercel CLI stay on the maintainer's Mac, with explicit approval. Force-pushing, rewriting shared history and deleting branches still need explicit authorization.

## Options considered

<!-- claude 2026-10-20: created: options considered -->

- **(a) Maintainer-gated pull requests.** This is the rule that `AGENTS.md` recorded before this decision: nothing merges without the maintainer's explicit authorization. Not chosen: six green pull requests waited about a day, and the maintainer said they do not want to review pull requests now ("the user does not want to review PRs", 2026-10-19; "I want to see the product and then I'll care", 2026-10-20). Source: the orchestrating session's reasoning from those quotes and the pull request list.
- **(b) Direct pushes to `main` after the full check.** This is the handoff's wording (spike memo, section 7, item 1). Not chosen: a direct push loses the bot review, the Vercel preview, the pull request as a record of what changed and why, and the pull request as the unit to revert. Source: the orchestrating session's reasoning.
- **(c) Pull requests that the orchestrating session merges when they are green and bot-clean.** Chosen. It keeps every automatic gate and the review record, and it does not make the maintainer a bottleneck.

## Consequences

<!-- claude 2026-10-20: created: consequences -->

- The maintainer's review happens on merged history. A problem found later is fixed with a revert or a follow-up pull request, not with a rejected merge.
- A pull request needs a clear description, because it is the record the maintainer reads afterwards. The existing rule on descriptions (purpose, scope, validation performed, limitations) applies unchanged.
- A bot finding blocks the merge until it is fixed or answered. A red check also blocks it.
- Releases do not change: tags, publication and the Vercel CLI still need the maintainer's explicit approval on the maintainer's Mac.

## Follow-ups

<!-- claude 2026-10-20: created: follow-ups -->

- `AGENTS.md`, "Change discipline and delivery workflow": the sentences that required the maintainer's review and authorization before a merge now state this rule (same pull request as this record).
- `docs/design/kyo-core.md`, section 1, "Recorded before the refactor", still says "Nothing is merged, force-pushed or published without the user's explicit approval." The bullet stays as written. The "Decision records" paragraph at the top of the design record says that this record replaces the bullet's merge rule, and that force-pushes and publication still need explicit approval (same pull request as this record).
- Comment on issue #51 that its process question is answered here, and on the tracking issue #17.
