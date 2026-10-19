# h34s: project direction and user requirements

These notes record the user's intended direction. They are not a description of an already implemented architecture, and proposed module names or APIs are not yet final.

## Project identity

- **nuxt4s** is the umbrella project (maintainer decision, 2026-10-19). **h34s** is the library inside it: the Scala.js modules over h3, and the npm tooling that links them into Nuxt. The library keeps its name. <!-- claude 2026-10-19: changed: the project name replaced by the nuxt4s umbrella and the h34s library (#21) -->
- h34s is a toolbox for running Scala.js server code in Nuxt/Nitro. Keep the effect-free native layer, the Kyo core API, the ZIO adapter, optional adapters, and npm tooling usable in the user's own project. Application models, services, databases, and deployment choices belong to that project.
- Keep the repository root for reusable libraries and shared build/test tooling. Put the runnable integration demo, including its Scala application code, under `examples/nuxt/`. The documentation site is a separate Nuxt application, today under `website/`; it moves to `docs/`, with the guides under `docs/content/` and the maintainer documents outside the content collection (#25). The Scala modules live under `scala-modules/<module>` once the `exts` level is removed (#23), and `zio-migrations` moves under `examples/` (#24). Label these roles explicitly; neither application is the h34s product or a required scaffold for consumers. <!-- claude 2026-10-19: changed: the site's move to docs/ and the planned Scala module layout (#23, #24, #25) -->
- The user authorized the private GitHub repository **yuri4n/h34s**, with remote `https://github.com/yuri4n/h34s.git`. Keep it private unless explicitly asked to change visibility. The user approved private GitHub Packages Maven publication under `dev.yuri4n`, starting with `0.1.0-alpha.1`, including a matching Git tag and GitHub release. Keep both repository and packages private. The Maven group moves to a nuxt4s namespace and the repository will be renamed `nuxt4s`; the exact group id, and whether h34s gets its own directory or its own repository, are open in #21. <!-- claude 2026-10-19: changed: appended the planned Maven group and repository rename (#21) -->
- A deliberate rename of the public coordinates is decided (#22): artifact ids `h34s-*`, Scala packages `h34s.*`, project ids to match, and no deprecated forwarders. Until that pull request lands, the current names stay; do not rename anything as a side effect of other work. <!-- claude 2026-10-19: changed: the no-rename rule replaced by the decided coordinate rename (#22) -->

## Highest priority: developer experience

**Maximize developer experience (DX).** Treat this as a first-class design requirement, alongside type safety and efficiency. Optimize for the code an application author writes, not only the implementation's convenience.

- Keep handler code short, idiomatic, composable, and easy to discover.
- Hide JavaScript interop, effect bridging, native conversions, casts, Promise/Future handling, and runtime execution inside library implementations.
- Application authors write ordinary Scala. Normal use needs no `js.native`, `js.Promise`, `js.Dynamic`, facades, casts, or TypeScript glue; JavaScript is only a workaround for an API h34s does not offer yet. Configuration, storage handles, and application resources come from Scala APIs.
- Do not make ordinary handlers configure conversion plumbing, construct transport objects, or manually import generated server files.
- Avoid introducing a large framework or a mandatory build-system overhaul before the basic handler API is satisfactory.

## Agreed effect architecture: Kyo core, ZIO adapter

- Keep one repository with separately publishable modules: an effect-free native layer (internal h3/Nitro facades and shared pure values), the Kyo core, a hand-written ZIO adapter over that core, and optional schema and storage bridges.
- **Kyo is the core and the lingua franca** (user decision, 2026-10-17). Implement the h3 transport, request lifecycle, cancellation, streaming, error rendering, configuration access, and application-scoped resources once, in Kyo. Kyo is the primary documented developer experience. Keep effect-specific dependencies out of the native layer.
- **ZIO is a first-class, hand-written adapter over the Kyo core.** ZIO users depend on Kyo transitively. Kyo users never depend on ZIO. The adapter runs ordinary ZIO programs on a ZIO `Runtime` and bridges their result, cancellation, finalizers, and streams into the core. It must keep every core behavior contract (interruption waits for ZIO finalizers, request scope, streaming); prove each one with tests. Do not use bridges whose interruption does not wait for finalizers, such as kyo-zio `ZIOs.get` or `ZStreams.get`, on request paths.
- Share transport contracts and behavior tests between the core and the adapter. Do not translate arbitrary ZIO programs into Kyo programs; run them on ZIO and bridge only at the boundary.
- Preserve existing call sites where practical; explicitly identify necessary error-typing changes. List every removed or renamed public API with its replacement in the migration guide.
- ZIO application handlers should remain ordinary ZIO programs using `for` comprehensions, `ZIO.service`, typed errors, and streams. Kyo application handlers should use native Kyo programs and `Env` composition.
- The desired application entry point is `AppHandler.define: request => ...`: a function from the request snapshot to the response body.
- `AppHandler` belongs to the example/application, not the reusable toolbox. It should be a thin composition over a library entry point such as `KHandler.define` or `ZHandler.define`, supplying application-specific services, resources, and policy.
- `AppHandler` is an optional name for code the application author writes, not an h34s export or generated object. Users may call `KHandler.define` or `ZHandler.define` directly when they do not need an application wrapper.
- The user chose the first-class Kyo API for the `yurian.land` application. Its handlers and service composition should use Kyo; the ZIO Notes example does not select the runtime for that downstream application.
- Offer ZIO users an API that is as ergonomic and idiomatic as the Kyo API. The Kyo API keeps its public names: `KHandler`, `Response`, `RuntimeConfig`, `Shared`, `KHubKv` (maintainer decision, 2026-10-19, #28). <!-- claude 2026-10-19: changed: the Kyo API naming question replaced by the decision (#28) -->

## HTTP model

- A handler receives an immutable `Request` snapshot and returns the response body. The body is a one-shot input read through `request.body.json[A]` or `request.body.text()`, with a size limit.
- Status, headers, and cookies are local state of the request computation: Kyo `Var[ResponseHead]`, ZIO `ZState[ResponseHead]`. h34s writes the head and body once, after the program and its finalizers finish. No public API mutates the native response while the program runs.
- On failure, the status always comes from the error. Only handler defaults, entries marked `OnFailure.Keep`, and the error's own headers survive; everything else the program set is discarded.
- `HandlerOptions` sets the wire format per handler: `{ "data": ... }` for values (or the raw value, with an explicit content type: text for strings, JSON otherwise), `{ "errors": [{ "message", "path"? }] }` for errors (or one `{ "error": ... }` for a single entry), 204 for `Unit`, streams of strings without an envelope. It also sets response defaults, the body limit, and whether JSON reads require a content type.
- `SafeError` (with `HttpError`, `InvalidInput`, and ZIO `ErrorResponse`) is the only error data a client may see. Every other `Throwable` is an opaque 500. A `SafeError` thrown by a decoder, converter or stored-value read is a defect, never an approved error. Diagnostics appear only in Nuxt development mode or debug mode (`NUXT_H34S_DEBUG=true`, or `runtimeConfig.h34s.debug: true` in `nuxt.config`; never derived from Nuxt's own `debug` option). A request can never enable them.
- **Client disconnects** (user decision, 2026-10-17). When the client disconnects, h34s interrupts the handler in both runtimes, waits for every request-scope and stream finalizer (asynchronous ones too, and the cleanup of work forked with structured combinators), writes nothing, and only then settles the request. Keep this order; do not settle first and clean up in the background. Guides must explain that interrupted work can roll back, and must show a tested way to make a step finish: `KHandler.uninterruptible(step)` for Kyo (Kyo's `Async.uninterruptible` alone does not delay the settle), `ZIO.uninterruptible` for ZIO. The disconnect tests in `HttpHandlerSuite` (Kyo) and `HandlerBoundarySpec` (ZIO) prove this rule.
- **Finalizer failures** (maintainer decision, 2026-10-19, #40): a finalizer that fails after the program produced a response is logged and does not change that response, in Kyo and in ZIO alike. A streamed body keeps its current behavior. <!-- claude 2026-10-19: created: the finalizer-failure decision (#40) -->
- **JSON content type** (maintainer decision, 2026-10-19, #41): from `0.3.0-alpha.1`, `request.body.json` requires a JSON content type by default; `HandlerOptions.requireJsonContentType` can turn it off per handler. <!-- claude 2026-10-19: created: the JSON content-type default decision (#41) -->

## Environment composition and lifecycle

- Prefer ordinary Kyo `Env`/`Layer` and ZIO `ZLayer`, `ZEnvironment`, and provisioning/scoping operations for application dependency composition. Do not require application authors to construct or pass a `Runtime`, use a custom dependency-injection container, or manage effect execution.
- `AppHandler` should compose application layers/environments around the handler program while the integration supplies request context and hides runtime execution.
- Distinguish application-scoped resources from request-scoped resources. Reusing a `ZLayer` value does not imply application-wide memoization, and a `ZEnvironment` does not itself own resource cleanup. h34s offers `Shared` (Kyo) and `ZShared` (ZIO) for application-scoped resources: built on first use, released when Nitro closes.
- Keep resources needed by a response stream alive until consumption completes or is cancelled, not merely until the stream value is returned.
- Configuration comes from a Scala API (`RuntimeConfig`, `ZRuntimeConfig`) over Nuxt `runtimeConfig` and its `NUXT_*` environment overrides. Wrap secrets in `Secret`; never log them or return them to clients.

## Conversions and efficiency

- `NativeConverter` stays the default type class that h34s APIs ask for. Schema support is optional. NativeConverter-only users must retain a complete supported path without schema dependencies, new implicit requirements, or ambiguous converter defaults.
- h34s internals, the example application, and `yurian.land` use one schema derivation per model that gives JSON, NativeConverter, and YAML (`derives Schema, NativeSchema`).
- Applications that choose a schema should define structural and domain validation once in their model schema. Handlers request validated models; they should not repeat null, trimming, or length checks that belong to that schema.
- Keep the Kyo Schema and ZIO Schema bridges optional and independent. Native values, NativeConverter, JSON, and YAML adapters should reuse that model definition where supported; do not claim a format or schema system works before its adapter is validated (for example, ZIO's YAML codec, zio-json-yaml, is JVM-only, so ZIO models have no YAML path on Scala.js).
- Optional bridges must be explicit about converter ownership and supported schema shapes. Do not install a blanket schema-derived NativeConverter that competes with an application's existing converter.
- Prefer `NativeConverter` and direct Scala.js/native-value conversion. Avoid JSON string round trips merely to move data between Scala and JavaScript; HTTP wire serialization is a separate concern.
- Keep conversions and runtime machinery out of endpoint business logic.
- Conversion integrations must compose predictably. Avoid duplicate defaults and ambiguous instances when users install multiple optional modules.
- Generated TypeScript must describe actual wire representations, including custom conversions. The generator describes what each `NativeConverter` writes (PR #15); a converter it cannot describe is typed `unknown`, never guessed. <!-- claude 2026-10-19: changed: scala-ts sentence replaced by the converter-driven generator rule (#27) -->
- Preserve streaming, cancellation, and resource lifecycle semantics across effect boundaries. Do not trade correctness for a superficially simple API or claim zero-cost interop without evidence.

## Modules and integrations

- The user wants modules to be independently deliverable and usable, with a smart compatibility strategy for converters and integrations. An application adds only the modules it needs.
- The current interpretation is separately publishable/consumable library artifacts, not separate deployed services; confirm if runtime deployment separation is intended.
- Dependencies point one way: native layer <- Kyo core <- {Kyo storage bridge, ZIO adapter} <- ZIO storage bridge. The schema bridges depend only on the native layer and their schema library. Kyo-only consumers never acquire ZIO; ZIO consumers acquire Kyo through the ZIO adapter. Keep a consumer check that fails when a Kyo artifact depends on ZIO.
- The user explicitly selected current Kyo with required tooling updates over an older Kyo release chosen only to retain the existing compiler pin. All modules now share one toolchain: Scala 3.9.0 with JDK 25 at build time, which Kyo 1.0.0-RC7 requires. Record compiler, linker, and build-JDK requirements in the compatibility profile. Validate ZIO behavior whenever the Kyo core or shared build tooling changes.
- Do not overbuild everyday pieces. Where a good library works out of the box (kyo-sql, kyo-schema-json/yaml, zio-schema-json, Skunk), guide users to it and show how to plug it into `AppHandler`. Do not add wrapper modules for them.
- Application services, database configuration, and example DTOs belong in examples, not mandatory core dependencies.
- The `0.1.0-alpha.1` release published eleven independently consumable modules (native, ZIO, Kyo, optional ZIO Schema, optional NuxtHub KV) with independent runtimes. The `0.2.0-alpha.1` release (published 2026-10-18 to the same private registry, with matching `v0.2.0-alpha.1` and `npm-v0.2.0-alpha.1` tags and prereleases) replaced that graph with nine modules on the Kyo core and the ZIO adapter; `docs/migration.md` lists every change. `version.sbt` controls the shared release version; `docs/releases.md` records coordinates and the tag/publish verification workflow. Future compatibility guarantees and broader module additions remain design decisions.
- Maintain an explicit compatibility matrix for the native layer, the Kyo core, the ZIO adapter, and the optional schema and storage integrations. Distinguish locally verified development combinations, CI/release validation, and untested combinations; list the evidence and platform constraints rather than implying broad support from one pinned build.
- CI gets a PostgreSQL 16 job for the Kyo example's `KyoSqlPostgresSuite` and the database part of `scripts/smoke.mjs` (maintainer decision, 2026-10-19, #29). PR #45 adds the job and its evidence; until it merges, no CI run on `main` checks the database path, and `docs/compatibility.md` lists it as not run. <!-- claude 2026-10-19: created: the PostgreSQL CI job decision (#29) -->
- Tie compatibility entries to tested build profiles: record the Nix lock/toolchain reference when available, OS/architecture, the shared sbt/pnpm pipeline, and CI evidence. Track Nix/NixOS support as pending until that profile is implemented and tested; a local non-Nix run does not establish Nix compatibility.

## Previously requested capabilities

- An unpublished `examples` module demonstrating a Nuxt frontend consuming Scala.js server handlers, a displayed stream, generated TypeScript types, and database writes.
- The database example runs inside Scala.js/Nitro using **Skunk**. The user reconfirmed Skunk after discussing Doobie: Doobie/JDBC would require a JVM backend. Models, database configuration, service layers, and AppHandler environment composition belong in the application modules. For Kyo applications, guide users to kyo-sql/kyo-sql-postgres used directly.
- Scala-to-TypeScript generation uses the converter-driven generator of PR #15 (`WireShape`, declarations written next to the linked modules), because scala-ts describes Scala types, not what a `NativeConverter` writes (maintainer decision, 2026-10-19, #27). Distinguish this from ScalablyTyped, which generates Scala facades from TypeScript. <!-- claude 2026-10-19: changed: scala-ts replaced by the converter-driven generator of PR #15 (#27) -->
- Improved code generation and Nuxt imports, potentially through an sbt plugin and Nuxt integration, without making endpoint authors manage the plumbing.
- Distinct public-safe errors and an unsafe `Throwable` channel. Only explicitly approved safe error data may be displayed; internal exceptions, causes, and defects must not leak to clients.
- The user explicitly wants development diagnostics tied to Nuxt development mode. In that mode, responses may include original errors, causes, and traces for debugging. Production remains opaque unless the operator turns on debug mode (`NUXT_H34S_DEBUG=true`, or `runtimeConfig.h34s.debug: true` in `nuxt.config`), which exposes the same diagnostics and must stay off in production. Compose Scala.js and Nitro source maps so Node debugging can locate the original Scala source.

## First-class sbt, Nix, and packaging support

- Support **sbt + Nix integration** and **sbt-native-packager** as first-class development, build, and application-packaging paths, with documentation and CI validation. The user does not require a plugin named `sbt-nix`; choose the integration mechanism after a bounded compatibility check.
- Keep one application build pipeline: Scala.js production linking and required type/code generation, followed by the Nuxt/Nitro build and application packaging. Nix should invoke that pipeline rather than maintain a competing build implementation. Never package stale development output.
- Use sbt-native-packager for deployable application distributions where appropriate. Keep this separate from publishing reusable Scala.js library artifacts. Start with one agreed production packaging target, not every package format.
- Nix should provide a pinned development toolchain and clean, sandboxed production builds, with explicit handling of Scala and JavaScript dependencies. A development shell alone does not establish build reproducibility. Define and test the supported OS/architecture matrix.
- The application runtime remains Scala.js inside Nitro/Node. Java and sbt are build-time tools, not required production runtimes. This is not a request for Scala Native or a separate JVM server.
- Keep ordinary sbt/pnpm workflows supported for consumers who do not use Nix. Do not make endpoint authors manage packaging internals.
- Keep secrets, environment files, database credentials, and mutable application data outside source closures, the Nix store, and packaged artifacts. Supply private configuration at runtime; keep PostgreSQL as an external service.
- Document development, customization, dependency updates, clean builds, packaging, and deployment. Validate the packaged example with requests, streaming/cancellation, and Skunk database operations. Distinguish planned commands from implemented and tested support.

## Writing style

Use a middle ground between ASD-STE100-inspired technical writing and a natural, friendly voice. The user values clarity without losing the conversational style of the project discussion. This is a house style, not a claim of formal ASD-STE100 compliance.

- Prefer short, direct sentences, active voice, familiar words, and explicit subjects. Keep technical terms consistent instead of varying them for style.
- Give one main action per instructional step. State prerequisites before the action, then the expected result and relevant recovery steps.
- Write guides as instructions to the application author: "Define your services," "Create your own `AppHandler`," and "Provide the layer before calling `ZHandler.define`." Avoid wording such as "The application supplies its services in `AppHandler`" when the reader still needs to write that code. Identify example-owned names before showing snippets that use them.
- Distinguish user setup from library behavior. Describe actual h34s behavior precisely (request context, effect execution, conversion, configured development loading); instruct the user to create models, compose environments, own resources, mount routes, and configure their application. Use descriptive wording when explaining existing implementation, without turning it into an automatic product capability.
- Keep API names, code identifiers, commands, and diagnostics exact. Explain necessary Scala, ZIO, Kyo, and Nuxt terminology; do not replace precise terms with misleading simplifications.
- Use a warm, natural tone in explanations and introductions. Allow contractions and occasional light humor when appropriate, but keep essential instructions free of idioms or jokes that obscure meaning.
- Use calm, literal language for errors, security guidance, destructive actions, and data-loss warnings. State the consequence and recovery action clearly.
- Keep explanations readable without enforcing rigid sentence limits or a restricted dictionary at the expense of technical accuracy. Avoid both dense prose and robotic, fragmented instructions.

## Documentation is part of developer experience

- Treat clear documentation and runnable examples as product requirements, not post-implementation extras. A good API must also be easy to install, configure, customize, learn, and troubleshoot.
- Cover three adoption paths: a project from scratch; adding Scala handlers incrementally to an existing Nuxt project while keeping existing TypeScript handlers; and adding Nuxt to an existing Scala.js/sbt project where compatible. Explain the Scala.js boundary and limitations for JVM-only projects and dependencies.
- Provide concise, verified quickstarts with prerequisites, supported versions, exact commands, expected results, and common failure recovery. Keep the first successful request simple; introduce customization progressively.
- Provide idiomatic Kyo and ZIO handler guides covering request decoding and validation, responses, safe/unsafe errors, services and environment composition, configuration and secrets, native conversions, generated TypeScript/client usage, streaming/cancellation, and testing.
- Explain application versus request lifetimes, development/watch behavior, production linking, deployment requirements, and version/converter compatibility. Do not require readers to reverse-engineer generated artifacts or implementation code to use the toolbox.
- Include a practical PostgreSQL guide running inside Scala.js/Nitro, with kyo-sql for Kyo and Skunk for ZIO: local database setup, configuration, connections/pooling, queries and writes, transactions, schema initialization/migrations, cleanup, and testing. Cover any optional convenience integrations with direct library-usage examples; keep database integrations optional and do not invent a mandatory persistence abstraction.
- Prefer compiling runnable examples as the source of documentation snippets. Validate Scala examples and generated TypeScript together, and exercise documented setup/build workflows from a clean state as part of release validation.
- Separate tutorials, task-focused recipes, API/configuration reference, and explanations of tradeoffs. Clearly distinguish supported behavior from planned APIs; never document unimplemented commands or packages as working features.
- Audit README files, guides, reference tables, homepage copy, SEO/LLM metadata, package descriptions, and examples together for ownership and product claims. Skunk and kyo-sql persistence are optional application choices, not database services supplied by h34s. Do not present ZIO, Schema, a database, or cloning this repository as mandatory dependencies. State plainly that ZIO users get Kyo transitively.

## Interactive documentation: explicit post-MVP priority

- The user wants compiler-derived type/signature information on Scala code in documentation, plus editable and runnable examples. These are not MVP requirements, but must not be forgotten or postponed indefinitely.
- Symbol navigation is also an explicit post-MVP goal: make resolved references clickable throughout documentation, including code blocks, inline code, and types/signatures inside hover popovers. Navigate to the appropriate API documentation or source definition, including external dependencies where supported.
- Reuse semantic symbol information across these surfaces, with version-aware link targets. Inline references without a compilable context may need an explicit source context or qualified symbol reference; do not guess targets from names alone or invent links for unresolved symbols.
- Run a bounded feasibility investigation, including symbol-link resolution, before committing to the documentation generation pipeline. Prefer existing tooling where compatible; mdoc, Metals/presentation-compiler APIs, and Scastie are candidates, not selected dependencies or verified integrations with this project.
- Separate static, build-time type-hover metadata; precompiled runnable demos; editable Scala that requires recompilation; and a full Nuxt/Scala.js/database playground. They have different implementation, hosting, and security requirements.
- Keep examples as compilable source with the correct Scala version, platform, dependencies, and source ranges so documentation can reuse them. Do not hand-maintain inferred types or present stale hover information after edits.
- Verify feasibility with representative ZIO and Kyo examples, including inferred environment/error/effect types. A browser-only Scala.js demo does not prove that Nitro handlers or Skunk database code work in that environment.
- Editable execution must be isolated from the documentation server and production resources. Explain third-party code submission, privacy, dependency availability, and hosting costs where applicable; never include secrets or production database credentials.
- Type information and symbol navigation must also be accessible through keyboard/focus and touch interactions, not mouse hover alone. Surfaces containing clickable links need an appropriate interactive popover pattern, not a non-interactive tooltip role; keep links reachable while moving focus or the pointer into the popover. Apply the UI skill requirements below when implementing these interfaces.

## UI work

- Consult and apply the global `better-ui` skill whenever creating, changing, or reviewing UI, including example applications and documentation interfaces.
- Preserve the project's existing component library, design tokens, and visual language while applying the skill's guidance. Report any UI checks that could not be verified.

## Change discipline and delivery workflow

A previous broad MVP implementation was rolled back at the user's request. Do not treat those discarded implementation choices as approved requirements. The user explicitly wants branch-, commit-, and PR-based delivery rather than large, unreviewed changes left in the working tree.

- Agree on the small public API and a bounded implementation scope before broad restructuring. Split larger work into coherent, independently reviewable changes; include relevant tests and documentation with each change.
- Inspect the working tree, index, current branch, and remotes before starting implementation. Establish a recoverable baseline first. Creating a branch does not snapshot or isolate uncommitted files.
- Preserve pre-existing uncommitted work. Do not sweep it into a task commit, stash it, discard it, or move it between branches without the user's agreement. If a baseline/checkpoint commit must include that work, agree on its scope first; a backup alone is not a substitute for a reviewed Git baseline.
- Implement approved tasks on dedicated feature/fix/documentation branches, not directly on the default branch. Use separate worktrees and disjoint scopes for concurrent implementation agents, starting from the agreed baseline rather than assuming uncommitted changes appear in another worktree.
- Make small, meaningful commits with explicit staging of intended paths or hunks. Keep changes reviewable and reversible; do not use blanket staging that could include unrelated work or secrets.
- Deliver changes through focused pull requests against the agreed base branch. Use draft PRs for unfinished work or early design review rather than treating unreviewed code as approved architecture.
- Before pushing or opening a PR, verify the intended remote, repository, base branch, and authentication. If these are missing or ambiguous, ask the user; do not invent a remote, publish to an arbitrary repository, or claim a PR exists when it does not.
- PR descriptions should state the purpose and scope, API/behavior changes, validation actually performed, remaining limitations, and migration or rollback considerations where relevant. Include UI verification and screenshots when applicable, without claiming checks that were not run.
- Do not merge PRs, force-push, rewrite shared history, or delete branches without explicit authorization. Keep independent work isolated until reviewed and integrated deliberately.
- No deprecated aliases before a non-alpha release (maintainer decision, 2026-10-19): a rename deletes the old name and lists it with its replacement in `docs/migration.md`. The `CommonEvHandler` alias is deleted with the coordinate rename. <!-- claude 2026-10-19: created: the no-deprecation policy (#22) -->

## Agent sessions

These rules apply to every agent session: on the maintainer's Mac (8 CPUs, 16 GB of memory) and in Claude Code cloud VMs (about 4 vCPUs, 16 GB).

- Read `docs/design/kyo-core.md` before you change the Kyo core, the ZIO adapter or a bridge. It records the user's decisions, the design and why the code differs from the first plan. When you change one of them, update it in the same pull request.
- Start from the tracking issue [#17](https://github.com/yuri4n/h34s/issues/17), "Roadmap and current state after 0.2.0-alpha.1". Every remaining item is a sub-issue of #17. When work lands, close its sub-issue and add a comment there with the pull request and the evidence. In a cloud session, read it with `gh api repos/yuri4n/h34s/issues/17`, `gh api repos/yuri4n/h34s/issues/17/comments` and `gh api repos/yuri4n/h34s/issues/17/sub_issues`, because `gh issue` uses GraphQL, which is blocked there. <!-- claude 2026-10-19: changed: sub-issues of #17 replace the checklist items -->
- Run at most 4 agents in parallel, and at most 2 that compile, link, build, install packages or run tests. Run one sbt process at a time: a second one competes for memory and for the same `target/` directories.
- Run one workflow at a time. Before a run of more than 10 agents, tell the user how many agents will run and what they do.
- Make each agent write its report file when it starts, and add each finding as soon as it is verified. Keep durable notes in the repository (through a pull request) or in the tracking issue. Session scratch space can disappear.
- Create release tags and use the Vercel CLI only on the maintainer's Mac, and only with the user's explicit approval.

### Models, shapes and briefs

#### Models

<!-- claude 2026-10-19: created: the maintainer's rules on models, verbatim -->

- The session model (Fable) orchestrates. It writes the briefs, reads the reports, records the decisions and talks to the user. It does not read diffs, run builds or write code.
- Every delegable agent runs on Opus: the implementer, the reviewer, the fixer, the finalizer, a search, a log reader, a carry or a format commit.
- Fable takes a seat only for: a design or plan panel, a gate review, and the skeptic of concurrency, resource-release or terminal-restore code. When in doubt, use Opus.
- In a workflow script: `model: 'opus'` on every agent except the skeptic, which omits `model` and inherits the session model.

#### Shapes (cheap cadence)

<!-- claude 2026-10-19: created: the maintainer's rules on phase shapes, verbatim -->

- Feature or refactor phase: three agents, in sequence. (1) Implementer: commits on the branch, does not push. (2) One review seat: a reviewer with a numbered checklist, or the skeptic for concurrency and restore code. Never both. (3) Fixer: applies the findings, runs the one full verification, runs the integration kit once, pushes.
- Mechanical phase (a move of callers, sinks, test-kit internals): one agent that implements, verifies and pushes. No review seat.
- The cut is the review seat, never the verification.
- One sbt (or build) process at a time. A phase owns the working tree from its first commit to its push. The orchestrator does not edit, commit or push while a phase runs, also when a stop hook asks for it.
- Each phase ends with a push and a report. The orchestrator then: starts CI on the branch, records the phase in the notes, and starts the next phase.

#### Briefs

<!-- claude 2026-10-19: created: the maintainer's rules on briefs, verbatim -->

- One context block, reused in every brief of a phase: repository, branch, HEAD, what is already done, the toolchain commands, the rule files, where the research notes and the sources are, the user's annotation and style rules, the commit trailers, the "never" list.
- Then the scope, numbered. For a refactor: the exact sites (a grep that lists them) and the reason each comment gives. For a feature: an audit step first (what the library defines, what the code does today), then the features with their semantics, then the tests, then the examples and docs.
- A return schema (StructuredOutput) with: commits, counts that must be zero, the design in two or three fields, test names, deviations, open issues. The orchestrator reads only this; it never reads the agent's transcript.
- The reviewer brief: the diff command, the report of the implementer, a checklist where every item is a question answered with evidence from the code, and "do not fix anything; do not run the build".
- The fixer brief: the findings, the order of the verification steps, the push command with retries, and "a failure is a root cause, never a flake; never skip, disable or loosen a test".
- Tell the fixer to start the full check early: an agent can be cut off by its turn budget. When that happens, start a one-agent finalizer that reads the check log, runs the kit and pushes; do not redo the implementation.

#### Safety nets of a rewrite

<!-- claude 2026-10-19: created: the maintainer's rules on safety nets, verbatim -->

- Before a pure function changes, copy the old code into the test sources as an oracle (verbatim, with the source commit in its header) and write a property that the new function equals the oracle on random inputs (explicit generators, 200 samples). A failing property means the new code is wrong, not the oracle.
- An incremental path has a law against its full path (step A to B equals a full frame of B). A performance change keeps the law and proves it.
- A bug fix starts with a negative control: a test that fails on the old code, with its failure text in the report.
- Lint the purity: one scalafix rule set, edge files with a two-line header for each foreign API, and a comment with a phase or KEEP on every other impure line. A script in CI checks the headers and the comments.

#### Docs and decisions

<!-- claude 2026-10-19: created: the maintainer's rules on docs and decisions, verbatim except the marked bullet -->

- Documentation is example-first: a feature ships with a README section whose code block is the text of an example region, compiled by a suite; Scaladoc with an example; an example app; its headless test leaves.
- Decisions are recorded as comments on the sub-issues of #17 and, when they change the design, in `docs/design/kyo-core.md`. Upstream issues are drafts in a notes directory; the user posts them. <!-- claude 2026-10-19: changed: the decision-log sentence replaced by sub-issue comments and the design record -->
- Annotate created or changed code at the smallest unit: `// claude <date>: created|changed: <what>`. One mark on a rewritten function; never a whole file; no stacked old marks: the history goes in the commit message.
