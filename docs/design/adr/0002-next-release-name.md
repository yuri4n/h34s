# ADR-0002: The next release is 0.3.0-alpha.1

<!-- claude 2026-10-20: created: title and status -->

- **Status:** accepted
- **Date:** 2026-10-20
- **Decided by:** the maintainer, by accepting the recommendation ("just take the recommended decisions", 2026-10-20)
- **Source:** [issue #51](https://github.com/yuri4n/h34s/issues/51), "Still open for the maintainer", version bullet; the [spike memo](https://github.com/yuri4n/h34s/blob/341b6f68b53fe051fd93731b149f894e6dbfe63b/docs/design/spike-sendstream-fetch.md), section 7, item 2; GitHub milestone 1, "0.3.0-alpha.1".

## Context

<!-- claude 2026-10-20: created: context -->

Two names for the next release were in use. The maintainer's handoff of 2026-10-19 calls it `0.2.0-alpha.2`. GitHub milestone 1 is "0.3.0-alpha.1", with a provisional scope, and pull requests #47, #48 and #49 also say `0.3.0-alpha.1`. `version.sbt` still says `0.2.0-alpha.1`, the published release.

The next release contains breaking changes. Decision 1 of issue #51 (streams are sent, not returned, see [ADR-0004](0004-streams-are-sent.md)) makes a returned stream a compile error. The milestone's provisional scope also names the public rename, with breaking coordinates.

## Decision

<!-- claude 2026-10-20: created: decision -->

The next release is named `0.3.0-alpha.1`, for the Maven artifacts and for the npm package `@yuri4n/h34s-nuxt`. `version.sbt` changes at release time, as `docs/releases.md` ("Validate and publish a release", step 2) describes. Release notes, migration notes and pull requests use this name from now on.

## Options considered

<!-- claude 2026-10-20: created: options considered -->

- **`0.2.0-alpha.2`**, the handoff's name. Not chosen, for three reasons from the spike memo (section 7, item 2): decision 1 of #51 is a breaking change; the only precedent, `0.1.0-alpha.1` to `0.2.0-alpha.1`, raised the minor version for breaking changes; and milestone 1 and three pull requests (#47, #48, #49) already use `0.3.0-alpha.1`.
- **`0.3.0-alpha.1`.** Chosen: it follows the precedent and matches the names already in use.

## Consequences

<!-- claude 2026-10-20: created: consequences -->

- Nothing changes in `version.sbt` or `packages/nuxt/package.json` until the release. The release steps in `docs/releases.md` stay as they are: the npm package is released first with the same version, then `version.sbt` changes, then the release notes are written and the tag is created.
- The milestone keeps its name. Its scope is still provisional; this record decides the name, not the content.

## Follow-ups

<!-- claude 2026-10-20: created: follow-ups -->

- `AGENTS.md`, "Modules and integrations": one line names `0.3.0-alpha.1` as the next release (same pull request as this record).
- Comment on issue #51 that its version question is answered here.
- Not decided here: who owns `.github/release-notes/unreleased.md`, which PRs #45, #48 and #49 each created (spike memo, section 7, item 6). The file is on `main` since PR #48 merged.
