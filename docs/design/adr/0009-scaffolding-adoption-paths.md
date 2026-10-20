# ADR-0009: Scaffold the three adoption paths with `h34s-scala init`

<!-- claude 2026-10-20: created: title and status -->

- **Status:** accepted
- **Date:** 2026-10-20
- **Decided by:** the maintainer. Covering all three paths, one-shot initialization and the agent prompt page are the maintainer's own decisions (2026-10-19). The answers to questions 1 to 6, 8 and 9 and the layout accept the memo's recommendations ("just take the recommended decisions", 2026-10-20). Question 7 is [ADR-0003](0003-registries-stay-private.md).
- **Source:** [issue #54](https://github.com/yuri4n/h34s/issues/54), "Decisions for the maintainer", questions 1 to 6, 8 and 9, and its comment of 2026-10-20; the [scaffolding memo](https://github.com/yuri4n/h34s/blob/1367f5b11c9bbc12da2480c6c22d3640ff44f960/docs/design/scaffolding-options.md), sections 1 to 5, 7 and 8.

## Context

<!-- claude 2026-10-20: created: context -->

The adoption guide (`docs/adoption.md`) describes three paths by hand: a new project, Scala handlers in an existing Nuxt app, and Nuxt in an existing Scala.js/sbt project. On 2026-10-19 the maintainer asked whether this setup can be scaffolded, and decided: cover all three paths; initialize once, without a regenerating step (the Nuxt module keeps owning generated glue such as routes); ship an agent prompt as a docs page whatever the mechanism; and pick the most ergonomic layout, as a recommendation.

The investigation found facts that force the design (memo, sections 1 and 2):

- With one `h34s.config.mjs`, the sbt root must be the Nuxt root: the CLI runs sbt from `--root` and resolves outputs there, and the module rejects output outside `rootDir` (section 2.1).
- Nuxt kit's module `onInstall` hook fires on the first Nuxt boot of each checkout, not on `nuxi module add`, and the h34s module is a plain function without kit, so the hook never runs today (sections 2.3 and 2.4).
- `nuxi module add` reads the npm token literally from `.npmrc`, so the documented `${GITHUB_PACKAGES_TOKEN}` line makes it fail (an inference from the code, not run; section 2.5).
- No mechanism can create the credential that downloads it (section 2.7). The registries stay private ([ADR-0003](0003-registries-stay-private.md)).
- PR #15 changes what a scaffold writes: no route files and no `entries` (section 2.9). PR #15 merged on 2026-10-20.

## Decision

<!-- claude 2026-10-20: created: decision -->

1. **One mechanism: `h34s-scala init [directory]` in the existing CLI of `@yuri4n/h34s-nuxt`**, run as `pnpm dlx @yuri4n/h34s-nuxt@<version> init`. There is no new initializer package (question 1, option a). It detects the path from the files present, plans and checks conflicts before it writes, creates missing files only and never overwrites one.
2. **No new module dependencies** (question 2, option a). `init` uses Node built-ins, and the module stays a plain function. When a `nuxt.config.ts` edit is not safe as a text edit, `init` prints the line to add.
3. **Templates follow PR #15's route-less model** (question 3, option a): no route files and no `entries`. PR #15 is merged first; it merged on 2026-10-20.
4. **One-shot per project** (question 4, option a). The committed `h34s.config.mjs` is the signal: when it exists, `init` stops with "already set up". There is no marker file and no `.nuxtrc` entry.
5. **pnpm is tested; scripts stay neutral; npm is untested; Yarn Plug'n'Play is unsupported** (question 5, option b). The scripts call `h34s-scala` and `node node_modules/nuxt/bin/nuxt.mjs` directly.
6. **The CLI gets an `sbtRoot` key in `h34s.config.mjs`** (question 6, option b).
7. **Layout** (memo, section 4): layout A for a new project and for an existing Nuxt app (`build.sbt` at the Nuxt root, Scala sources in `scala-server/`, sbt project `server`). Layout B for an existing sbt build: the Nuxt app in `web/` below the sbt root, a new `h34s.sbt` next to the untouched `build.sbt`, and `sbtRoot: '..'` in `web/h34s.config.mjs`.
8. **`init` writes the Nuxt half of a new project** (question 8, option a): a minimal Nuxt app pinned to a supported Nuxt version. The guide documents Nuxt's own starter followed by path 2 as the alternative.
9. **Template defaults as proposed** (question 9): Kyo as the default API; `.jvmopts` with `-Xss4m -Xmx4g`; `nitro.preset: 'node-server'`; the Kyo locale exclusion as a commented line, not active; no `NODE_OPTIONS` heap setting; the names `scala-server/` and `server`; the command name `init`.
10. **Credentials stay a once-per-machine prerequisite.** `init` writes only environment-variable references, never a token (issue #54, recommendation 3).
11. **Also ship** the agent prompt page (delivered by PR #56, merged on 2026-10-20) and a module error hint that points at `init`.

## Options considered

<!-- claude 2026-10-20: created: options considered -->

### Mechanisms (memo, section 3)

<!-- claude 2026-10-20: created: mechanism options -->

- **The agent prompt page alone.** It covers all three paths, and the author's coding assistant writes both halves by hand. Not chosen as the only mechanism: it works today, but results vary by assistant and nothing tests them. It ships anyway, next to `init` (the maintainer's decision of 2026-10-19).
- **`onInstall`, with `@nuxt/kit` (`defineNuxtModule`).** Not chosen: it serves path 2 only, because paths 1 and 3 have no Nuxt app yet and a Nuxt app below the git root triggers a monorepo skip. It needs the npm token first, and its usual entry, `nuxi module add`, fails with the documented `.npmrc`. It runs once per checkout (`.nuxtrc`), and a skip in CI or without a TTY still marks it done. It fires at a surprising time, once per clone. It adds a runtime dependency. Cost M, 2 to 4 days.
- **`onInstall`, without kit** (hand-attached `getMeta` and `onInstall`). Not chosen: the same problems, and it relies on kit's internal module shape. Cost M, 2 to 3 days.
- **File-exists scaffolding in the module's `setup`** (the `@nuxt/eslint` pattern). Not chosen: it serves path 2 only, and it writes an sbt build during a Nuxt boot without asking, on every boot, CI and `postinstall: nuxt prepare` included. The module also throws first unless the code is reordered. The memo keeps this pattern only for the error hint.
- **`h34s-scala init` in the existing CLI.** Chosen: one command for every path, testable from a clean directory, with versions tied to the package's own version. It needs only the npm token to download, runs without Nuxt or sbt, and writes both halves. Cost M, 4 to 6 days.
- **A `create-*` initializer** (`pnpm create @yuri4n/h34s` runs `@yuri4n/create-h34s`). Not chosen: it is natural for path 1 only, and it needs the npm token first, because the package is private. While private it is `pnpm dlx … init` with one more package to release in lockstep, plus 1 to 2 days for a second package and its publish job. Its high value for path 1 needs a public package.
- **A giget template** (`nuxi init -t gh:yuri4n/h34s/templates/<x>#<tag>`). Not chosen: path 1 only; it needs `GIGET_AUTH` with read access to the private repository, a second token type; it downloads the whole repository tarball per run; it copies files verbatim, without parameters, so it needs one template per API and cannot set the package name. Adding `-M @yuri4n/h34s-nuxt` then hits the `nuxi module add` token problem.
- **`sbt new` with a giter8 template.** Not chosen: path 1 only. It is familiar to Scala users, but it needs a separate `.g8` repository or subdirectory, git access to it, and Maven Central for its resolver, and its versions can drift from the npm package. Cost M, 3 to 5 days.
- **An sbt plugin task** (for example `h34sInit`). Not chosen: path 3 only. It needs the Maven credentials in the meta-build before it can download, so the setup lines it needs almost equal the lines it saves. Cost L, 6 to 10 days, for a new published sbt 1.x plugin on Scala 2.12 with scripted tests and release changes.

### Questions (issue #54 and memo, section 8)

<!-- claude 2026-10-20: created: question options -->

- **Question 1, (b) a private `@yuri4n/create-h34s`.** Not chosen: it adds a package with the same credential prerequisite. **(c) a public unscoped `create-h34s` on npmjs.** Not chosen: it conflicts with "keep packages private"; revisit it with the registry question ([ADR-0003](0003-registries-stay-private.md)).
- **Question 2, (b) `@nuxt/kit` for `defineNuxtModule` and `onInstall`, or (c) `magicast` for robust `nuxt.config` edits.** Not chosen: `onInstall` is not in the plan, and the text edit falls back to a printed instruction. The memo notes that if `nuxi module add` ever becomes a supported path, a `nuxt` or `@nuxt/kit` devDependency also silences its "not a Nuxt module" prompt.
- **Question 3, (b) build on `main` now and rework later.** Not chosen: the maintainer's rule "the module owns generated glue such as routes" is PR #15's model. On the old `main`, the scaffold would write route files that the author then maintains by hand. The memo also tied this to the pending scala-ts versus PR #15 decision; issue #27 closed on 2026-10-20, when PR #15 merged.
- **Question 4, (b) once per checkout, through `.nuxtrc`.** Not chosen: it re-runs on every fresh clone and in CI, and it records a skip as done.
- **Question 5, (a) pnpm only, or (c) pnpm, npm and Yarn, all tested.** Not chosen: option (b) costs nothing extra and claims nothing untested. The memo gives no other reason.
- **Question 6, (a) keep layout C unsupported and write layout B as two configs** (`h34s.config.mjs` plus a rebased `h34s.build.config.mjs`, and scripts with `--root .. --config web/h34s.build.config.mjs`). Not chosen: it costs one more file and an untested flag combination. The `sbtRoot` key is small (about one day), removes the second config and the cross-directory flags, makes layout C work, and lets the example drop `examples/nuxt/h34s.build.config.mjs`.
- **Question 8, (b) delegate to Nuxt's starter, then run `init` as path 2.** Not chosen as the default. The memo gives no explicit reason against it. It notes that the Nuxt version the starter installs is unverified, and it recommends documenting (b) as the alternative for authors who want Nuxt's starter.
- **Question 9.** The memo proposes the defaults and asks to accept them or name what to change. It gives no alternative set.

### Layouts (memo, section 4)

<!-- claude 2026-10-20: created: layout options -->

- **Layout A: `build.sbt` at the Nuxt root, a `scala-server/` subproject.** Chosen for paths 1 and 2: it needs no CLI change, it is what the guide's snippet already encodes, and it mirrors the only fresh Nuxt consumer the repository runs (`scripts/check-npm-nuxt.mjs`). For path 3, `init .` in the sbt root also gives layout A, with no CLI change. It is not the default there: layout B keeps an existing Scala repository's root and IDE import unchanged (the memo's reason for B).
- **Layout B: the Nuxt app below the sbt root (`<sbt>/web/`).** Chosen for path 3: an existing Scala repository keeps its root and its IDE import unchanged. Without `sbtRoot` it needs two configs and cross-directory flags; with `sbtRoot` it needs one config. Scala sources outside `web/` keep their paths in development source maps but lose embedded content, unless the module also embeds from `sbtRoot`.
- **Layout C: sibling builds (`<repo>/scala-server/build.sbt`, `<repo>/web/`).** Not a template layout. It cannot be driven today; with `sbtRoot` it works with one config (`sbtRoot: '../scala-server'`). The memo recommends A and B for the templates and gives no further reason against C.

## Consequences

<!-- claude 2026-10-20: created: consequences -->

- Every path starts with a once-per-machine token and two environment variables ([ADR-0003](0003-registries-stay-private.md)). `init` never prints a token value.
- `init` does not install packages or run sbt, so it stays fast and works offline. Each later step fails with its own tool's message.
- The npm package version pins the Maven version that `init` writes, so the npm and Maven pair stays valid.
- The guides must not describe `init` before it ships: the agent prompt page (PR #56) describes today's manual setup, and the docs switch is the last pull request of the plan.
- The homepage mini-project view ([ADR-0010](0010-homepage-mini-project-view.md)) shows layout A.

## Follow-ups

<!-- claude 2026-10-20: created: follow-ups -->

- Deliver the plan in five reviewable pull requests (memo, section 7): (1) the prompt page, delivered by PR #56; (2) `sbtRoot` and the error hint; (3) `init` with templates and unit tests; (4) the end-to-end check from a clean directory with real sbt and the published packages; (5) the docs switch to `init`. The memo estimates about 11 to 16 implementer days plus 5 to 6 days of review and fixes. <!-- claude 2026-10-20: changed: item 2 delivered, the hint moves to item 3 -->
  - Item 2's `sbtRoot` key is delivered by the pull request of branch `feat/sbt-root`: the CLI runs sbt in `sbtRoot`, the module embeds the Scala sources from it, the example drops its second, rebased config, and the adoption guide's path 3 uses layout B. The module error hint is not in it: the hint names `init`, which does not exist before item 3, so it ships with item 3.
- Fix the adoption guide's prose ("Add an sbt build next to it") together with the layout change (memo, section 4). Done in the same pull request as item 2. <!-- claude 2026-10-20: changed: the prose fix is done -->
- Comment on issue #54 with the answers and a link to this record.
