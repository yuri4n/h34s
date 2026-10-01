# Build and run the recovered playground

This guide covers the current ZIO/Scala.js playground. It is not the final h34s installation guide. Kyo, generated application TypeScript types, safe errors, and the new example application are still planned.

**Use this server locally. The recovered error boundary can expose exception details. Do not deploy it publicly yet.**

## Prerequisites

- Git and access to the private `yuri4n/h34s` repository.
- JDK 17 and an sbt launcher. `project/build.properties` selects sbt **1.11.7**; you do not need a separate Scala installation.
- Node **24.x** and pnpm **10.34.5**. `package.json` pins pnpm for Corepack and checks the tool versions.
- Internet access on the first build. sbt resolves Maven dependencies, ScalablyTyped installs its npm input, and pnpm downloads frontend dependencies. The inherited font module also downloads fonts during a cold Nuxt build.
- Python 3 and C/C++ build tools if a native npm dependency has no prebuilt binary for your system. On macOS, install the Xcode Command Line Tools. On Linux, install your distribution's compiler, make, and Python packages.

PostgreSQL and database credentials are **not** needed for `/api/test` or the current stream tests. The retained Nuxt Content module uses a local SQLite database; that is separate from the planned Skunk/PostgreSQL example.

## Start from a clean checkout

Do not merge this repository's new history into the old, dirty recovery workspace. Use a separate checkout:

```sh
git clone https://github.com/yuri4n/h34s.git
cd h34s
```

The build-foundation instructions require the build-foundation change. When reviewing its PR before merge, check out `build/foundation` in this clone.

If you use Corepack, enable its pnpm shim with `corepack enable pnpm`. Corepack then reads the exact version from `package.json`. Corepack is optional; an independently installed pnpm 10.34.5 also works.

1. Install the locked dependencies and allow the project's approved native build scripts:

   ```sh
   pnpm install --frozen-lockfile
   ```

2. Run the existing Scala stream tests:

   ```sh
   pnpm run scala:test
   ```

   Expect **9 passing tests**. The two suites run sequentially: the recovered Scala.js/ZIO test runner hung when both ran concurrently on the validation host. Tests within each suite keep their existing behavior.

3. Start development:

   ```sh
   pnpm dev
   ```

   This links Scala.js for development before starting Nuxt. Use the local URL printed by Nuxt. The retained `/api/test` route is intended to stream `abc`, but the HTTP smoke test currently exposes a recovered stream-adapter defect. See **Known runtime failure** below; do not use this route as a successful streaming example yet.

Nuxt watches TypeScript and Vue files. `pnpm dev` does **not** watch Scala files automatically. In another terminal, run:

```sh
sbt '~nuxtsvr/nuxtDevLink'
```

The watcher reruns development linking after Scala changes and replaces the output imported by the existing route. Stop it before production builds: development and production publish into the same import directory. Do not run two link/staging tasks concurrently in one checkout.

## Build and test the production output

```sh
pnpm build
node scripts/check-scala-output.mjs production
node --test scripts/smoke-build.test.mjs
```

`pnpm build` runs `nuxtsvr/nuxtProdLink` before Nuxt. A failed Scala compile or link stops the build; it does not fall back to development output. The staging check compares every emitted file and its contents with the production linker output. The smoke test starts the built Nitro server on loopback, requests the Scala route, checks the intended `abc` response, and stops the process. It needs no database or secrets. **The smoke test currently fails**, as described below; successful compilation does not establish a working HTTP stream.

### Known runtime failure

The built `/api/test` route returns `{"errors":["Error: [h3] Invalid or incompatible stream provided."]}` instead of `abc`. `SH3Event.sendStream` passes a `ReadableStreamReader` to h3, while h3 expects a stream with `pipeTo` or `pipe`. The converter tests only consume the reader, so they do not catch this transport mismatch. This also demonstrates the existing error-disclosure problem.

The HTTP test is intentionally not skipped or changed to accept the error. It is a failing regression check for a focused stream-boundary follow-up, not a reason to broaden this build change into the handler redesign.

To start that output yourself:

```sh
node .output/server/index.mjs
```

Node runs the application. Java and sbt are build-time tools only. The retained frontend includes native npm dependencies: build on the same OS/architecture as the intended runtime. This is a build smoke check, not approval for public deployment or a portable packaged release.

### How linking works

- `nuxtsvr / Compile / fastLinkJS` and `fullLinkJS` retain separate default linker directories under `scala-modules/svr/target/`.
- `nuxtDevLink` and `nuxtProdLink` stage the selected output into ignored `server/.scala/` **after** linking succeeds. Staging replaces the whole directory, so removed modules and files from a previous mode cannot remain there.
- `server/api/test.ts` keeps its existing import. No route or handler API changes are required.
- `sbt nuxtsvr/clean` removes the staged directory as well as the module's build output.
- Compilation no longer includes `-rewrite`. Existing migration and unused-symbol warnings remain; builds do not automatically edit source files.
- Shared dependencies append to the Scala.js plugin's defaults, preserving its test bridge. This fixes the recovered aggregate `sbt test` linker error instead of bypassing modules with no tests.

Use `pnpm build`, not a direct `nuxt build`, for the complete pipeline. Direct Nuxt commands bypass Scala linking. `pnpm build:scala` and `pnpm dev:scala` remain aliases for compatibility. `pnpm generate` also links for production first, but static hosting does not provide a live Scala API; use Nitro/Node for handlers.

### Types and facades

`pnpm run scala:facades` compiles the h3 facade module and triggers ScalablyTyped as needed. The historical `scala:types` name remains an alias. **This translates TypeScript declarations to Scala facades, not Scala DTOs to TypeScript.** The scala-ts plugin is declared but application type generation is not wired yet.

## Troubleshooting

| Symptom                                                   | Action                                                                                                                                                                                     |
| --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| pnpm reports an unsupported version                       | Use Node 24 and pnpm 10.34.5. With Corepack, run it from the checkout so it reads `package.json`.                                                                                          |
| `Could not locate the bindings file` for `better-sqlite3` | A prior install may have used `--ignore-scripts`. Run `pnpm rebuild better-sqlite3`, then build again. If rebuilding fails, check Python and your C/C++ toolchain.                         |
| A linked Scala module is missing                          | Run `pnpm run scala:fast` for development or `pnpm build` for production. Do not create generated modules by hand.                                                                         |
| A production build picked up a development change         | Stop the Scala watcher before building. Do not share one checkout's staging directory between concurrent builds.                                                                           |
| `Expected one nuxtsvr-opt directory`                      | Old Scala versions may have left multiple target directories. Remove only the obsolete generated directories under `scala-modules/svr/target/`, then rebuild.                              |
| sbt reports a Java heap error                             | Increase the launcher's heap, for example `JAVA_OPTS='-Xmx2G -Xss4M' pnpm build`.                                                                                                          |
| Nuxt typed-router warns `No routes defined`               | This is an inherited frontend warning. It does not establish that the API route is missing; use the production smoke test to check it. Frontend route typing remains a separate follow-up. |

Do not put secrets in build files or generated source. Keep environment files, database credentials, and mutable data out of Git and future package/Nix build inputs.

## Validation and limits

Local validation uses macOS x86_64, JDK 17.0.16, Node 24.17.0, and pnpm 10.34.5. Dependencies came from an existing local cache; this is not a clean-machine installation test.

- Frozen pnpm installation: passed. A separate `pnpm rebuild better-sqlite3` repaired the earlier baseline install that had disabled lifecycle scripts.
- Aggregate `pnpm run scala:test`: passed, 9 tests, after preserving Scala.js test-bridge dependencies. The initial concurrent wrapper run timed out; sequential suites passed.
- Development and production linking: passed. Staged output matched the selected linker, including removal of a deliberately stale development file when switching to production.
- `pnpm build`: completed before the test-bridge correction. A later combined test/link/rebuild command reached successful production linking and Nuxt server compilation, then hit its six-minute command limit during final Nitro bundling. That later full command did not complete.
- Production HTTP smoke test: failed with the stream-adapter error above. This remains a real failing regression test.
- Nix and clean CI: see [Nix development toolchain](nix.md) and the PR's actual check results.

The build fixes two recovered `TaskStream` imports that prevented the application from compiling. It does not change service behavior. Running the inherited npm facade build normalized five redundant `peer: true` flags in its lockfile; package versions, integrity hashes, and dependency edges are unchanged. npm also reports 39 vulnerabilities in that inherited development-tool graph (14 moderate, 23 high, 2 critical); no automatic audit fix or dependency upgrade was applied. The existing Unit converter warning, experimental database services, peer-version warnings, error leakage, and frontend typed-router warning remain outside this change.

The finite-stream tests do not establish correct backpressure, cancellation, stream resource lifetimes, or error redaction. PostgreSQL operations, generated TypeScript DTOs, browser rendering, accessibility, and deployment remain unverified. Those need focused implementation and tests before the MVP can be called ready.
