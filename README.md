# h34s

A Scala.js toolbox for Nuxt/Nitro server handlers, with developer experience as a first-class requirement.

**Status: buildable ZIO/Scala.js playground, not a verified MVP or a published library.** The build foundation preserves the recovered handler API. Kyo, safe errors, the new examples module, and application packaging remain planned.

**Do not expose the recovered server publicly. Its error boundary can leak internal exception details.**

## What is here

- `scala-modules/exts/`: the existing h3/ZIO wrappers, native-conversion integrations, stream conversion and tests, and experimental migration helpers.
- `scala-modules/svr/`: the existing application services, Skunk/PostgreSQL groundwork, handler, and SQL resources.
- `server/api/test.ts`: a route that re-exports linked Scala.js output. Generated output is not committed.
- `app/` and `content/focuses/`: the retained non-CV frontend and shared UI. These are legacy playground content, not the final h34s example application.
- `build.sbt`, `project/`, and dependency lockfiles: the recovered build inputs, including the ScalablyTyped npm lockfile.

Start with [Build and run](docs/build.md) for prerequisites, exact commands, the current test scope, and troubleshooting. A [locked Nix development shell](docs/nix.md) provides an alternative to installing the build tools individually. Ordinary compilation no longer rewrites sources, and `pnpm build` stages production Scala.js output before building Nuxt. The [baseline report](docs/baseline.md) records the original import and its limitations.

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
