# h34s

A Scala.js toolbox for Nuxt/Nitro server handlers, with developer experience as a first-class requirement.

**Status: recovered playground baseline, not a verified MVP or a published library.** This import preserves the existing ZIO-based implementation for review. It does not introduce a Kyo core, a new handler API, or a production packaging pipeline.

## What is here

- `scala-modules/exts/`: the existing h3/ZIO wrappers, native-conversion integrations, stream conversion and tests, and experimental migration helpers.
- `scala-modules/svr/`: the existing application services, Skunk/PostgreSQL groundwork, handler, and SQL resources.
- `server/api/test.ts`: a route that re-exports linked Scala.js output. Generated output is not committed.
- `app/` and `content/focuses/`: the retained non-CV frontend and shared UI. These are legacy playground content, not the final h34s example application.
- `build.sbt`, `project/`, and dependency lockfiles: the recovered build inputs, including the ScalablyTyped npm lockfile.

See [the baseline report](docs/baseline.md) for exclusions, validation, and known limitations. Existing scripts are historical build inputs, not a verified quickstart. In particular, the current Scala compiler options include `-rewrite`, and the production output path is not fully wired.

## Intended direction

- Kyo as the core effect system, with an optional ZIO integration that preserves idiomatic ZIO handler code.
- Application-owned `AppHandler.define`, standard ZIO layer/environment composition, and request/stream resource lifetimes.
- Direct native conversions, generated TypeScript wire types through scala-ts, and separate public-safe and unsafe error channels.
- An unpublished Nuxt example with streaming and Skunk database operations inside Scala.js/Nitro.
- First-class sbt/Nix development and application packaging with sbt-native-packager, while retaining a non-Nix workflow.
- Verified setup and adoption guides; compiler-derived interactive documentation as an explicit post-MVP priority.

These are requirements, not claims of implemented support. [AGENTS.md](AGENTS.md) records the detailed direction and delivery rules. Public APIs, module boundaries, artifact names, supported versions, and packaging targets still need focused review.

## Contributing

Use small feature, fix, or documentation branches and focused pull requests. Include relevant tests and documentation with each change. Do not treat this recovered source layout or its known limitations as approved final architecture.
