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

   Expect **12 passing tests** in three suites. sbt runs the two spec files one after the other, because the recovered Scala.js/ZIO test runner hung when they ran concurrently on the validation host.

3. Start development:

   ```sh
   pnpm dev
   ```

   This links Scala.js for development before starting Nuxt. Use the local URL printed by Nuxt. The retained `/api/test` route streams `abc`. See **Stream transport** below for what this route does and does not demonstrate.

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

`pnpm build` runs `nuxtsvr/nuxtProdLink` before Nuxt. A failed Scala compile or link stops the build; it does not fall back to development output. The staging check compares every emitted file and its contents with the production linker output. The smoke test starts the built Nitro server on loopback, requests the Scala route, checks the intended `abc` response, and stops the process. It needs no database or secrets. Successful compilation does not establish a working HTTP stream; the smoke test does, for a finite stream of strings.

### Stream transport

<!-- Changed by Claude Code on 2026-10-13: this section replaced "Known runtime failure" after the stream transport fix. -->

`SH3Event.sendStream` hands h3 a Web `ReadableStream`. h3 pipes it into the Node response with `pipeTo` and ends the response when the stream closes. An earlier version passed the stream's reader instead, and h3 rejected it with `[h3] Invalid or incompatible stream provided`. The smoke test above is the regression check for that boundary. The converter tests in `StreamConversionSpec` also exercise the stream form through a `WritableStream`, the same way h3 consumes it.

The `/api/test` route demonstrates a finite stream of strings. It does not demonstrate the following, and the fix did not change them:

- The source stream runs eagerly when the `ReadableStream` starts. It does not wait for `pull` requests, so Web Streams backpressure does not apply.
- Each native chunk goes to Node's `response.write` unchanged, so it must be a string, a `Buffer`, or a `Uint8Array`. The compiler does not check this. Any other value fails at runtime: as the first chunk it returns a 500 response with Node's error message, and after the first chunk it leaves the response open.
- If the source fails after h3 writes the first chunk, the status stays 200 and the server does not end the response. The client receives the chunks sent so far and then waits until it, a proxy, or the operating system closes the connection. Nitro logs nothing, because the event is already handled.
- A client disconnect does not stop the source stream. h3 1.15 does not cancel the `ReadableStream` when the response closes, so the ZIO stream runs to its end and its later writes are dropped.
- Nitro's in-process fetch, which SSR `useFetch` and `$fetch` use, does not pipe the stream. h3 hands it to `new Response(...)`, where only `Uint8Array` chunks are readable.
- The error boundary is unchanged. A failure before the first chunk returns a 500 response that can expose the exception message.

Ending the response on a late failure, cancelling the source on disconnect, backpressure, chunk encoding, and the safe-error boundary are separate follow-ups.

To start that output yourself:

```sh
node .output/server/index.mjs
```

Node runs the application. Java and sbt are build-time tools only. The retained frontend includes native npm dependencies: build on the same OS/architecture as the intended runtime. This is a build smoke check, not approval for public deployment or a portable packaged release.

### How linking works

- `nuxtsvr / Compile / fastLinkJS` and `fullLinkJS` retain separate default linker directories under `scala-modules/svr/target/`.
- `nuxtDevLink` and `nuxtProdLink` stage the selected output into ignored `server/.scala/` **after** linking succeeds. Staging replaces the whole directory, so removed modules and files from a previous mode cannot remain there.
- `server/api/test.ts` keeps its existing import. No route or handler API changes are required. `h3` is a direct runtime dependency at **1.15.4**, matching the facade input; linked ESM must not depend on finding a transitive or ancestor-checkout installation.
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
| A streaming route sends part of its data and never finishes | The source stream failed after the first chunk. The current transport does not end the response in that case; see **Stream transport**. Reproduce the failure in a converter test and check the handler's log. Ending the response on failure is a planned follow-up. |
| Nuxt typed-router warns `No routes defined`               | This is an inherited frontend warning. It does not establish that the API route is missing; use the production smoke test to check it. Frontend route typing remains a separate follow-up. |

Do not put secrets in build files or generated source. Keep environment files, database credentials, and mutable data out of Git and future package/Nix build inputs.

## Validation and limits

Local validation uses macOS x86_64, JDK 17.0.16, Node 24.17.0, and pnpm 10.34.5. Dependencies came from an existing local cache; this is not a clean-machine installation test.

- Frozen pnpm installation: passed. A separate `pnpm rebuild better-sqlite3` repaired the earlier baseline install that had disabled lifecycle scripts.
- Aggregate `pnpm run scala:test`: passed, 9 tests, after preserving Scala.js test-bridge dependencies. The initial concurrent wrapper run timed out; sequential suites passed.
- Development and production linking: passed. Staged output matched the selected linker, including removal of a deliberately stale development file when switching to production.
- `pnpm build`: completed before the test-bridge correction. A later combined test/link/rebuild command reached successful production linking and Nuxt server compilation, then hit its six-minute command limit during final Nitro bundling. That later full command did not complete.
- Production HTTP smoke test: failed with the stream-adapter error. The stream transport fix, validated below, resolved it.
- Nix and clean CI: see [Nix development toolchain](nix.md) and the PR's actual check results.

The build fixes two recovered `TaskStream` imports that prevented the application from compiling. It does not change service behavior. Running the inherited npm facade build normalized five redundant `peer: true` flags in its lockfile; package versions, integrity hashes, and dependency edges are unchanged. npm also reports 39 vulnerabilities in that inherited development-tool graph (14 moderate, 23 high, 2 critical); no automatic audit fix or dependency upgrade was applied. The experimental database services, peer-version warnings, error leakage, and frontend typed-router warning remain outside these changes. The stream transport fix also removed the duplicate `NativeConverter[Unit]` from the test handler. Its string result reached h3 after the stream ended and raised a hidden `ERR_HTTP_HEADERS_SENT`; the smoke test passed either way, so that part is a cleanup, not the fix.

<!-- Changed by Claude Code on 2026-10-13: validation record for the stream transport fix. -->
Validation of the stream transport fix used a Linux x86_64 container with the locked Nix shell: JDK 17.0.20, Node 24.19.0, and pnpm 10.34.5. Maven Central rate-limited the container, so sbt and coursier resolved through a Maven Central mirror. That changes where the artifacts came from, not which versions were used.

- Frozen pnpm installation: passed.
- Aggregate `pnpm run scala:test`: passed, 12 tests, including the three new transport tests.
- Development and production linking and both staging checks: passed, including removal of the injected stale development file.
- `pnpm build`: completed, including Nitro bundling.
- Production HTTP smoke test: passed. A manual request to the built server returned status 200 with the chunked body `abc`. Nitro does not log errors for an already-handled event, so a quiet server log is not evidence on its own.
- The build did not modify tracked files.

The finite-stream tests do not establish correct backpressure, cancellation, stream resource lifetimes, or error redaction. PostgreSQL operations, generated TypeScript DTOs, browser rendering, accessibility, and deployment remain unverified. Those need focused implementation and tests before the MVP can be called ready.
