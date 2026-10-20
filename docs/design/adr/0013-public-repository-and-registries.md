# ADR-0013: The repository is public, releases stay alpha, and the registries go public after the coordinate decision

<!-- claude 2026-10-20: created: title and status -->

- **Status:** accepted
- **Date:** 2026-10-20
- **Decided by:** the maintainer: "I want h34s public! It's for the people, that's why I want to focus on DX. Let's just use alpha releases." (2026-10-20). The orchestrating session wrote down the consequences.
- **Source:** [issue #21](https://github.com/yuri4n/h34s/issues/21), the [comment of 2026-10-20](https://github.com/yuri4n/h34s/issues/21#issuecomment-6293131572) that records the decision; the orchestrating session of 2026-10-20.
- **Supersedes:** [ADR-0003](0003-registries-stay-private.md), "The package registries stay private this round".

## Context

<!-- claude 2026-10-20: created: context -->

ADR-0003 kept the Maven and npm registries private for one round. It started from the rule in `AGENTS.md`, "Keep both repository and packages private." It also noted a contradiction that it did not resolve: on 2026-10-20 the GitHub API reports the repository `yuri4n/h34s` as public (`gh api repos/yuri4n/h34s --jq .private` returns `false`).

On 2026-10-20 the maintainer resolved it. The public repository is intended. h34s is "for the people", and that is why developer experience comes first.

The published releases, `0.1.0-alpha.1` and `0.2.0-alpha.1`, are on GitHub Packages: the Maven artifacts under `dev.yuri4n` and the npm package `@yuri4n/h34s-nuxt`. The two registries set visibility in different ways:

- GitHub's Maven registry supports only [repository-scoped permissions](https://docs.github.com/en/packages/learn-github-packages/about-permissions-for-github-packages#permissions-for-repository-scoped-packages): a package inherits the visibility of the repository that publishes it. The Maven artifacts became public together with the repository. Any GitHub user with a token can download them.
- GitHub's npm registry supports [granular permissions](https://docs.github.com/en/packages/learn-github-packages/about-permissions-for-github-packages): the package has its own visibility setting, separate from the repository's. Making the repository public did not change it. The session that wrote this record could not read the package settings, so this record does not state the npm package's visibility.

Installing from GitHub Packages needs a GitHub token with `read:packages` in both cases, also for a public package.

"For the people" means installing without a token (issue #21 comment). GitHub Packages requires a token even for public packages, so the public path is Maven Central for the Scala artifacts and npmjs for the Nuxt module. Both depend on the coordinate decision of issue #21:

- Maven Central needs the group id and a verified namespace for it.
- npmjs needs the npm scope.

The artifact ids `h34s-*` and the packages `h34s.*` are already decided (#22). The first public release should not ship names that change a week later (issue #21 comment).

## Decision

<!-- claude 2026-10-20: created: decision -->

1. The repository `yuri4n/h34s` is public.
2. Releases stay alpha (`0.x.y-alpha.n`) until the API settles. Breaking changes are allowed between alphas, without deprecated aliases, as `AGENTS.md` already says. The next release is `0.3.0-alpha.1` ([ADR-0002](0002-next-release-name.md)).
3. The registries go public after the coordinate decision of issue #21: the Scala artifacts on Maven Central, the npm package on npmjs. The first public release uses the final names.
4. Until then, the releases stay on GitHub Packages, and installing them needs a token. The documentation states the visibility of the Maven artifacts and of the npm package separately, as the Context does. It does not describe Maven Central or npmjs publication before it exists.

## Options considered

<!-- claude 2026-10-20: created: options considered -->

- **Stay private** ([ADR-0003](0003-registries-stay-private.md)). Not chosen: the maintainer wants h34s public.
- **Public repository, packages on GitHub Packages, as the end state.** Not chosen: consumers would still need a token to install, which contradicts the purpose. It stays the state until issue #21 is decided.
- **Publish publicly on GitHub Packages.** Not chosen as the public path: GitHub Packages requires a token even for public packages, the Maven registry included (issue #21 comment). The Maven artifacts are public there now, and every download still needs a token.
- **Public registries now, under the current names.** Not chosen: the coordinates change with issues #21 and #22, and a public first release should not ship names that change a week later (issue #21 comment).
- **Maven Central and npmjs after issue #21.** Chosen: consumers install without a token, under the final names.

## Consequences

<!-- claude 2026-10-20: created: consequences -->

- ADR-0003 is superseded. Its status block says so; its content stays as written.
- The documentation loses its "keep private" instructions and its "the repository is private" and "the Maven artifacts are private" statements: `AGENTS.md`, the design record, the maintainer guides, `docs/README.md`, the root README, the site's homepage and metadata, and the guides and reference pages. They now say that the repository is public, that the Maven artifacts became public with it, and that every download from GitHub Packages needs a token. The npm pages still describe the npm package as private, which this record does not verify (Context).
- Installing still needs a GitHub token with `read:packages` until the public registries exist. The install instructions change when issue #21 lands and the first public release is published.
- The Maven artifacts have no access boundary beyond the token: any GitHub user with a classic personal access token with `read:packages` can download them. They are built from the public source. If a private boundary is needed again, publish them from a separate private repository, because GitHub's Maven registry ties a package's visibility to the repository that publishes it.
- The full Git history is public now. A secret scan of the full history is part of the launch cleanup (#57).
- [ADR-0009](0009-scaffolding-adoption-paths.md) rejected a public unscoped `create-h34s` initializer on npmjs because it conflicted with "keep packages private". That reason no longer holds. This record does not decide the initializer; it can be revisited together with the npm scope in issue #21.

## Follow-ups

<!-- claude 2026-10-20: created: follow-ups -->

- Issue #21: the maintainer confirms the group id and the npm scope. The issue #21 comment recommends `dev.yuri4n.nuxt4s` and asks whether the `@nuxt4s` npm scope is the maintainer's or free. Then #22 does the rename and the public publishing setup.
- Maintainer-only setup, once, before the first public release (issue #21 comment): a Sonatype Central account and its publishing token, a GPG signing key, and an npmjs account and token, stored as repository secrets.
- Issue #57 (launch cleanup): a secret scan of the full history, and public install instructions that replace the token steps once issue #21 lands.
- Comment on issue #21 that this record captures the decision.
- The maintainer reads the visibility of `@yuri4n/h34s-nuxt` in its package settings, so that the documentation can state it.
