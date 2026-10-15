# h34s: project direction and user requirements

These notes record the user's intended direction. They are not a description of an already implemented architecture, and proposed module names or APIs are not yet final.

## Project identity

- The user has chosen **h34s** as the project name.
- The user authorized the private GitHub repository **yuri4n/h34s**, with remote `https://github.com/yuri4n/h34s.git`. Keep it private unless explicitly asked to change visibility. Published package/artifact namespaces remain to be agreed.
- Do not rename the existing workspace directory, Scala packages, or build artifacts as a side effect of recording the name.

## Highest priority: developer experience

**Maximize developer experience (DX).** Treat this as a first-class design requirement, alongside type safety and efficiency. Optimize for the code an application author writes, not only the implementation's convenience.

- Keep handler code short, idiomatic, composable, and easy to discover.
- Hide JavaScript interop, effect bridging, native conversions, casts, Promise/Future handling, and runtime execution inside library implementations.
- Do not make ordinary handlers configure conversion plumbing, construct transport objects, or manually import generated server files.
- Avoid introducing a large framework or a mandatory build-system overhaul before the basic handler API is satisfactory.

## Agreed effect architecture and ZIO API philosophy

- Keep one repository with separately consumable modules: thin native h3 bindings, an idiomatic ZIO API, and a first-class Kyo API.
- ZIO is the primary documented developer experience. ZIO handlers use the ZIO runtime; Kyo handlers use the Kyo runtime. Keep effect-specific dependencies out of the native h3 bindings.
- Share native transport contracts and behavior tests. Do not translate arbitrary ZIO programs into Kyo or introduce a mandatory dual-runtime bridge. A shared Kyo core is only a future option after explicit interoperability, cancellation, and lifecycle validation.
- Preserve existing call sites where practical; explicitly identify necessary error-typing changes.
- Application handlers should remain ordinary ZIO programs using `for` comprehensions, `ZIO.service`, typed errors, and streams.
- The desired application entry point is `AppHandler.define: ...`.
- `AppHandler` belongs to the example/application, not the reusable toolbox. It should be a thin composition over a library entry point such as `ZHandler.define`, supplying application-specific services, resources, and policy.
- Offer an equally ergonomic, idiomatic Kyo API for applications that do not use ZIO. The user has also called this “KIO”; confirm public naming before finalizing it.

## Environment composition and lifecycle

- Prefer ordinary `ZLayer`, `ZEnvironment`, and ZIO provisioning/scoping operations for application dependency composition. Do not require application authors to construct or pass a `Runtime`, use a custom dependency-injection container, or manage effect execution.
- `AppHandler` should compose application layers/environments around the handler program while the integration supplies request context and hides runtime execution.
- Distinguish application-scoped resources from request-scoped resources. Reusing a `ZLayer` value does not imply application-wide memoization, and a `ZEnvironment` does not itself own resource cleanup.
- Keep resources needed by a response stream alive until consumption completes or is cancelled, not merely until the stream value is returned.

## Conversions and efficiency

- Schema support is optional. NativeConverter-only users must retain a complete supported path without schema dependencies, new implicit requirements, or ambiguous converter defaults.
- Applications that choose a schema should define structural and domain validation once in their model schema. Handlers request validated models; they should not repeat null, trimming, or length checks that belong to that schema.
- Keep ZIO Schema and eventual Kyo Schema integrations as optional, independent bridges. Native values, NativeConverter, JSON, and YAML adapters should reuse that model definition where supported; do not claim all formats or schema systems are implemented before their adapters are validated.
- Optional bridges must be explicit about converter ownership and supported schema shapes. Do not install a blanket schema-derived NativeConverter that competes with an application's existing converter.
- Prefer `NativeConverter` and direct Scala.js/native-value conversion. Avoid JSON string round trips merely to move data between Scala and JavaScript; HTTP wire serialization is a separate concern.
- Keep conversions and runtime machinery out of endpoint business logic.
- Conversion integrations must compose predictably. Avoid duplicate defaults and ambiguous instances when users install multiple optional modules.
- Generated TypeScript must describe actual wire representations, including custom conversions. Do not assume arbitrary converter implementations can be inferred automatically by scala-ts.
- Preserve streaming, cancellation, and resource lifecycle semantics across effect boundaries. Do not trade correctness for a superficially simple API or claim zero-cost interop without evidence.

## Modules and integrations

- The user wants modules to be independently deliverable and usable, with a smart compatibility strategy for converters and integrations.
- The current interpretation is separately publishable/consumable library artifacts, not separate deployed services; confirm if runtime deployment separation is intended.
- Kyo is a first-class independent integration, not a mandatory runtime beneath ZIO. Kyo-only consumers should not need ZIO, and ZIO-only consumers should not need Kyo.
- The user explicitly selected current Kyo with required tooling updates over an older Kyo release chosen only to retain the existing compiler pin. Keep the effect modules independently consumable and record compiler, linker, and build-JDK requirements in the compatibility profile. Validate existing ZIO behavior when shared build tooling changes.
- Application services, database configuration, and example DTOs belong in examples, not mandatory core dependencies.
- Final module boundaries, naming, release/version policy, and converter compatibility rules remain design decisions to agree on.
- Maintain an explicit compatibility matrix for the native, ZIO, and optional schema integrations. Distinguish locally verified development combinations, CI/release validation, and untested combinations; list the evidence and platform constraints rather than implying broad support from one pinned build.
- Tie compatibility entries to tested build profiles: record the Nix lock/toolchain reference when available, OS/architecture, the shared sbt/pnpm pipeline, and CI evidence. Track Nix/NixOS support as pending until that profile is implemented and tested; a local non-Nix run does not establish Nix compatibility.

## Previously requested capabilities

- An unpublished `examples` module demonstrating a Nuxt frontend consuming Scala.js server handlers, a displayed stream, generated TypeScript types, and database writes.
- The database example runs inside Scala.js/Nitro using **Skunk**. The user reconfirmed Skunk after discussing Doobie: Doobie/JDBC would require a JVM backend. Models, database configuration, service layers, and AppHandler environment composition belong in the application modules.
- Scala-to-TypeScript generation using **scala-ts**. Distinguish this from ScalablyTyped, which generates Scala facades from TypeScript.
- Improved code generation and Nuxt imports, potentially through an sbt plugin and Nuxt integration, without making endpoint authors manage the plumbing.
- Distinct public-safe errors and an unsafe `Throwable` channel. Only explicitly approved safe error data may be displayed; internal exceptions, causes, and defects must not leak to clients.
- The user explicitly wants development diagnostics tied to Nuxt development mode. In that mode, responses may include original errors, causes, and traces for debugging. Production remains opaque. Compose Scala.js and Nitro source maps so Node debugging can locate the original Scala source.

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
- Include a practical PostgreSQL/Skunk guide running inside Scala.js/Nitro: local database setup, configuration, connections/pooling, queries and writes, transactions, schema initialization/migrations, cleanup, and testing. Cover any optional convenience integrations with direct library-usage examples; keep database integrations optional and do not invent a mandatory persistence abstraction.
- Prefer compiling runnable examples as the source of documentation snippets. Validate Scala examples and generated TypeScript together, and exercise documented setup/build workflows from a clean state as part of release validation.
- Separate tutorials, task-focused recipes, API/configuration reference, and explanations of tradeoffs. Clearly distinguish supported behavior from planned APIs; never document unimplemented commands or packages as working features.

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
