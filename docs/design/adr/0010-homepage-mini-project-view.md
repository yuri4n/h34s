# ADR-0010: Homepage mini-project view: an in-place Kyo | ZIO toggle and a project tour component

<!-- claude 2026-10-20: created: title and status -->

- **Status:** accepted
- **Date:** 2026-10-20
- **Decided by:** the maintainer, by accepting the recommendation ("just take the recommended decisions", 2026-10-20). The idea is the maintainer's own (2026-10-19).
- **Source:** [issue #52](https://github.com/yuri4n/h34s/issues/52), "Constraints already known" and "Decisions for the maintainer", decisions 1 to 3.

## Context

<!-- claude 2026-10-20: created: context -->

The maintainer likes the homepage code widget (`website/app/pages/index.vue`, the `Handlers.scala` card with the `POST /api/kyo/greeting` response shape) and asked to make it a mini-project view: several files, an instant Kyo and ZIO comparison, the configuration the project needs (`build.sbt`, `nuxt.config.ts`, `h34s.config.mjs`) and the project structure.

Issue #52 records the constraints:

- **Truth source.** The widget's code is hand-written in `index.vue`, while the real handler lives in `examples/nuxt/`. Copies drift. `AGENTS.md` asks to compile runnable examples as the source of snippets, and this view is also the first brick of the post-MVP interactive documentation (issue #33).
- **Layout dependency.** The tree and the configuration files depend on the layout that the scaffolding decision fixes ([ADR-0009](0009-scaffolding-adoption-paths.md)).
- **Route files** depend on PR #15, and a streaming tab depends on the `sendStream` work ([ADR-0004](0004-streams-are-sent.md)).
- **UI rules.** The `better-ui` skill applies, and keyboard, focus and touch must work.

## Decision

<!-- claude 2026-10-20: created: decision -->

1. **Comparison:** an in-place Kyo | ZIO toggle, with the same slots in both states and the differing lines highlighted (decision 1, option a).
2. **Scope:** the homepage stays single-file, plus the Kyo | ZIO switch. A reusable "project tour" component holds the tree, the tabs and the configuration files. The homepage links to it, and the guides embed it (decision 2, option a).
3. **Timing:** the build starts after the scaffolding layout is fixed and after PR #15 merges, in a fresh session, with issue #52 as the brief (decision 3, option a).
4. **Source of the files:** the component reads its files from `examples/nuxt` at build time, with source ranges. No hand-copied code remains in `index.vue` (issue #52, "Done when").

## Options considered

<!-- claude 2026-10-20: created: options considered -->

### Comparison interaction (decision 1)

<!-- claude 2026-10-20: created: options for the comparison -->

- **(a) An in-place toggle.** Chosen: it is layout-stable, accessible and works on touch.
- **(b) An aligned two-pane view at desktop widths.** Not chosen. The issue recommends (a) for the reasons above and gives no other reason against (b).
- **(c) A draggable split divider between two panes.** Not chosen. The issue recommends (a) for the reasons above and gives no other reason against (c).
- **A reveal-mask "image compare" slider.** Not recommended: code lines do not align, lines are half revealed, and it has no keyboard or screen-reader story.

### Scope (decision 2)

<!-- claude 2026-10-20: created: options for the scope -->

- **(a) A single-file homepage plus a reusable project tour component.** Chosen.
- **(b) Everything on the homepage.** Not chosen. The issue recommends (a) and gives no reason against (b).

### Timing (decision 3)

<!-- claude 2026-10-20: created: options for the timing -->

The issue gives one recommendation and no alternative.

## Consequences

<!-- claude 2026-10-20: created: consequences -->

- The homepage code can no longer drift from the example, because the build reads it from `examples/nuxt`.
- The tree shows the layout that `init` generates for a new project: layout A ([ADR-0009](0009-scaffolding-adoption-paths.md)).
- Both gates are now met on paper: PR #15 merged on 2026-10-20, and ADR-0009 fixes the layout. `init` itself is not built yet, so the tree follows the decided layout, not a generated project.
- The UI work must report every keyboard, focus and touch check that it could not verify.

## Follow-ups

<!-- claude 2026-10-20: created: follow-ups -->

- Start the build in a fresh session with issue #52 as the brief. Its "Done when" list stays the acceptance test.
- Comment on issue #52 with the answers and a link to this record.
