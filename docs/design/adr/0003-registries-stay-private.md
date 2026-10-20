# ADR-0003: The package registries stay private this round

<!-- claude 2026-10-20: created: title and status -->

- **Status:** superseded by [ADR-0013](0013-public-repository-and-registries.md) <!-- claude 2026-10-20: changed: superseded by ADR-0013 -->
- **Date:** 2026-10-20
- **Decided by:** the maintainer, by accepting the recommendation ("just take the recommended decisions", 2026-10-20)
- **Source:** [issue #54](https://github.com/yuri4n/h34s/issues/54), "Decisions for the maintainer", question 7; the [scaffolding memo](https://github.com/yuri4n/h34s/blob/1367f5b11c9bbc12da2480c6c22d3640ff44f960/docs/design/scaffolding-options.md), section 2.7 and section 8, question 7; [issue #21](https://github.com/yuri4n/h34s/issues/21) and its comment of 2026-10-19.

## Context

<!-- claude 2026-10-20: created: context -->

h34s publishes to private GitHub Packages: the Maven artifacts under `dev.yuri4n` and the npm package `@yuri4n/h34s-nuxt`. `AGENTS.md` says "Keep both repository and packages private."

On 2026-10-20 the GitHub API reports the repository `yuri4n/h34s` as public (`gh api repos/yuri4n/h34s` returns `"private": false, "visibility": "public"`). That contradicts `AGENTS.md` ("Keep it private unless explicitly asked to change visibility") and `docs/releases.md` ("repository/package visibility remains private"). According to GitHub's documentation, a Maven package on GitHub Packages takes the visibility of its repository, while an npm package can have its own visibility. The cloud session that wrote this record cannot read the package settings, so the packages' current visibility is not checked. This record keeps the premise that `AGENTS.md` states, private registries, until the maintainer confirms the intended visibility.

The scaffolding investigation (issue #54) found that no mechanism can create the credential that downloads it (memo, section 2.7). While the registries are private, every scaffolding path, `h34s-scala init` included, starts with a GitHub token. The orchestrating session noted the same consequence when it accepted this recommendation.

Going public is coupled with issue #21. The maintainer decided on 2026-10-19 that nuxt4s is the umbrella, that the Maven group moves to a nuxt4s namespace, and that the repository will be renamed `nuxt4s`. Still open there: the exact group id, the npm scope (GitHub Packages requires the scope to be the repository owner, so `@yuri4n` stays while the packages are private), and who renames the repository and when.

## Decision

<!-- claude 2026-10-20: created: decision -->

1. The Maven and npm registries stay private for this round.
2. Scaffolding templates keep the registry lines (the resolver, the credentials and the `.npmrc` scope) in one template block, so that going public deletes lines instead of changing a flow.
3. Anything public is published once, under the final names. Nothing is published publicly under `@yuri4n` first and renamed later.
4. The decision to go public is made together with issue #21: the group id, the repository rename and the npm scope.

## Options considered

<!-- claude 2026-10-20: created: options considered -->

- **(a) Private for this round.** Chosen (memo, section 8, question 7). Its costs are known and stated: every mechanism needs a token first, and the cloud sessions cannot read the private registry (memo, section 7, assumptions).
- **(b) Public under the new group and scope before the scaffold ships.** Not chosen. The memo gives no explicit reason against (b). The orchestrating session derives one from the memo's rule to publish anything public once, under the final names: issue #21 has not fixed those names yet. The rename to `nuxt4s` is decided there, but the exact group id, the npm scope, and who renames the repository and when are still open.
- **A public unscoped `create-h34s` initializer on npmjs** (scaffolding memo, section 8, question 1, option c). Not chosen: it conflicts with "keep packages private". The memo says to revisit it together with this question. See [ADR-0009](0009-scaffolding-adoption-paths.md).

## Consequences

<!-- claude 2026-10-20: created: consequences -->

- Every adoption path starts with a once-per-machine prerequisite: a classic GitHub token with `read:packages`, exposed as `GITHUB_PACKAGES_USER` and `GITHUB_PACKAGES_TOKEN`, and the `@yuri4n` scope in `~/.npmrc` (memo, section 5, "Before you start").
- Scaffolding writes only environment-variable references, never a token value (issue #54, recommendation 3).
- This record does not change `AGENTS.md`, which already says to keep the repository and the packages private. The repository's reported public visibility (see Context) contradicts that line; this record does not resolve the contradiction.

## Follow-ups

<!-- claude 2026-10-20: created: follow-ups -->

- Comment on issue #54 (question 7) and on issue #21 that the registries stay private until #21 settles the names.
- The maintainer confirms the intended visibility of the repository and the packages. If the public repository is intended, a new record supersedes this one, and `AGENTS.md` and `docs/releases.md` change with it.
- When #21 is decided, write a new record that decides visibility, and supersede this one.
