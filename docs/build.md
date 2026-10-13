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

   <!-- Changed by Claude Code on 2026-10-13: test count after the Unit result and stream boundary tests. -->
   Expect **23 passing tests** in five suites. sbt runs the zio-h3 spec files one after the other, because the recovered Scala.js/ZIO test runner hung when they ran concurrently on the validation host. The `SendStreamBoundarySpec` suite starts h3 on loopback ports and imports `h3` at run time, so it needs the installed dependencies from step 1.

3. Start development:

   ```sh
   pnpm dev
   ```

   <!-- Changed by Claude Code on 2026-10-13: the streaming route works under pnpm dev; its limits moved to "Stream transport". -->
   This links Scala.js for development before starting Nuxt. Use the local URL printed by Nuxt. The retained `/api/test` route streams `abc`; a request through the development server returned status 200 with that body during validation. See **Stream transport** below for what this route does and does not demonstrate.

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

<!-- Changed by Claude Code on 2026-10-13: the smoke test also covers the failing stream route. -->
`pnpm build` runs `nuxtsvr/nuxtProdLink` before Nuxt. A failed Scala compile or link stops the build; it does not fall back to development output. The staging check compares every emitted file and its contents with the production linker output. The smoke test starts the built Nitro server on loopback, requests the Scala route, checks the intended `abc` response, and stops the process. A second test requests the deliberately failing route `/api/test-stream-failure`, checks that the response ends with a transport error, and checks that `/api/test` still answers. It needs no database or secrets. Successful compilation does not establish a working HTTP stream; the smoke test does, for a finite stream of strings and for a stream that fails after its first chunks.

### Stream transport

<!-- Changed by Claude Code on 2026-10-13: this section replaced "Known runtime failure" after the stream transport fix, then gained the failure and disconnect behavior. -->

`SH3Event.sendStream` hands h3 a Web `ReadableStream`. h3 pipes it into the Node response with `pipeTo` and ends the response when the stream closes. An earlier version passed the stream's reader instead, and h3 rejected it with `[h3] Invalid or incompatible stream provided`. The smoke test above is the regression check for that boundary. The converter tests in `StreamConversionSpec` also exercise the stream form through a `WritableStream`, the same way h3 consumes it. `SendStreamBoundarySpec` goes one step further: it mounts the handler in an h3 app on a loopback port and checks the HTTP responses with Node's `fetch`.

What happens at the edges of a stream:

- **The source fails before the first chunk.** No headers were sent yet. The handler answers with status 500 and the body `{"errors":["<message>"]}`. This is the existing, unsafe error boundary; see the last bullet below.
- **The source fails after the first chunk.** The status and the chunks sent so far are already with the client, so no error body can follow. The server destroys the response socket. The client receives an incomplete chunked body and its HTTP client reports a transport error; Node's `fetch` rejects `response.text()` with `TypeError: terminated`. The server logs one `[zio-h3]` line with the failure messages and keeps serving. Before this change, the response stayed open until the client, a proxy, or the operating system closed the connection.
- **The client disconnects before the stream ends.** Node emits `close` on the response. `sendStream` interrupts the source stream at its next element, its finalizers run, and the handler finishes with `Unit`. Before this change, the ZIO stream ran to its end and its later writes were dropped.
- **The handler fails with an `ErrorResponse`**, for example through `ZHandler.failWith(404, ...)`. The response keeps the status the handler set and carries the handler's messages. Before this change, the error boundary matched that failure as a plain `Throwable`, set status 500, and sent `{"errors":[null]}`.

The `/api/test` route demonstrates a finite stream of strings and `/api/test-stream-failure` demonstrates a late failure. Neither demonstrates the following, and this change did not alter them:

- The source stream runs eagerly when the `ReadableStream` starts. It does not wait for `pull` requests, so Web Streams backpressure does not apply.
- Each native chunk goes to Node's `response.write` unchanged, so it must be a string, a `Buffer`, or a `Uint8Array`. The compiler does not check this. Any other value fails at runtime: as the first chunk it returns a 500 response with Node's error message, and after the first chunk it ends the response like any other late failure.
- After `sendStream`, the handler must finish with `Unit`. <!-- Changed by Claude Code on 2026-10-13: the Unit conversion moved into the toolbox. --> `defineEventHandler` converts `()` to JavaScript `undefined` itself, through `ResultConverter`; handler code imports no converter for this, and a `NativeConverter[Unit]` in scope does not change it. `UnitResultSpec` checks that conversion. Any other result makes h3 raise an `ERR_HTTP_HEADERS_SENT` that Nitro hides and that no current test detects.
- Nitro's in-process fetch, which SSR `useFetch` and `$fetch` use, does not pipe the stream. h3 hands it to `new Response(...)`, where only `Uint8Array` chunks are readable. That path has no Node socket, so the disconnect handling above does not apply to it.
- The error boundary is unchanged. A failure before the first chunk returns a 500 response that can expose the exception message, and a defect keeps the status the handler had set.

Backpressure, chunk encoding, and the safe-error boundary are separate follow-ups.

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

<!-- Changed by Claude Code on 2026-10-13: added the row about a streaming route that never finishes. -->
| Symptom                                                   | Action                                                                                                                                                                                     |
| --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| pnpm reports an unsupported version                       | Use Node 24 and pnpm 10.34.5. With Corepack, run it from the checkout so it reads `package.json`.                                                                                          |
| `Could not locate the bindings file` for `better-sqlite3` | A prior install may have used `--ignore-scripts`. Run `pnpm rebuild better-sqlite3`, then build again. If rebuilding fails, check Python and your C/C++ toolchain.                         |
| A linked Scala module is missing                          | Run `pnpm run scala:fast` for development or `pnpm build` for production. Do not create generated modules by hand.                                                                         |
| A production build picked up a development change         | Stop the Scala watcher before building. Do not share one checkout's staging directory between concurrent builds.                                                                           |
| `Expected one nuxtsvr-opt directory`                      | Old Scala versions may have left multiple target directories. Remove only the obsolete generated directories under `scala-modules/svr/target/`, then rebuild.                              |
| sbt reports a Java heap error                             | Increase the launcher's heap, for example `JAVA_OPTS='-Xmx2G -Xss4M' pnpm build`.                                                                                                          |
| A streaming client reports a terminated or incomplete body    | The source stream failed after the first chunk. The server ended the response on purpose; see **Stream transport**. Look for the `[zio-h3]` line in the server log, which carries the failure messages, and reproduce the failure in a converter test. |
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

<!-- Changed by Claude Code on 2026-10-13: the Unit converter sentence at the end of this paragraph. -->
The build fixes two recovered `TaskStream` imports that prevented the application from compiling. It does not change service behavior. Running the inherited npm facade build normalized five redundant `peer: true` flags in its lockfile; package versions, integrity hashes, and dependency edges are unchanged. npm also reports 39 vulnerabilities in that inherited development-tool graph (14 moderate, 23 high, 2 critical); no automatic audit fix or dependency upgrade was applied. The experimental database services, peer-version warnings, error leakage, and frontend typed-router warning remain outside these changes. The stream transport fix also removed the duplicate `NativeConverter[Unit]` from the test handler. Its string result reached h3 after the stream ended and raised a hidden `ERR_HTTP_HEADERS_SENT`; the smoke test passed either way, so that part is a cleanup, not the fix.

<!-- Changed by Claude Code on 2026-10-13: validation record for the stream transport fix. -->
Validation of the stream transport fix used a Linux x86_64 container with the locked Nix shell: JDK 17.0.20, Node 24.19.0, and pnpm 10.34.5. Maven Central rate-limited the container, so sbt and coursier resolved through a Maven Central mirror. That changes where the artifacts came from, not which versions were used.

- Frozen pnpm installation: passed.
- Aggregate `pnpm run scala:test`: passed, 12 tests, including the three new transport tests. A later run with the cancel test: 13 tests passed.
- Development and production linking and both staging checks: passed, including removal of the injected stale development file.
- `pnpm build`: completed, including Nitro bundling.
- Production HTTP smoke test: passed. A manual request to the built server returned status 200 with the chunked body `abc`. Nitro does not log errors for an already-handled event, so a quiet server log is not evidence on its own.
- The build did not modify tracked files.
- Development server: `pnpm dev` on a loopback port served `/api/test` with status 200 and the chunked body `abc`.

<!-- Changed by Claude Code on 2026-10-13: validation record for the Unit result conversion. -->
Validation of the `Unit` result conversion used a Linux x86_64 container with the locked Nix shell: JDK 17.0.20, Node 24.19.0, and pnpm 10.34.5. Maven Central rate-limited the container again, so sbt and coursier resolved through a Maven Central mirror; the versions are unchanged. `scripts/ci-build.sh` passed end to end on the committed tree:

- Frozen pnpm installation: passed.
- Aggregate `pnpm run scala:test`: passed, 18 tests in four suites, including the five tests in `UnitResultSpec`. The zio-h3 test module now links as CommonJS (`main.cjs`) and imports `h3` at run time.
- Development and production linking and both staging checks: passed, including removal of the injected stale development file.
- `pnpm build`: completed, including Nitro bundling. The build did not modify tracked files.
- Production HTTP smoke test: passed. `/api/test` returned status 200 with the body `abc`.

The smoke test cannot see the hidden `ERR_HTTP_HEADERS_SENT`, so `UnitResultSpec` is the regression check for the `undefined` result. A debug run of that spec during validation also showed a pre-existing error-boundary defect that this change does not fix: `mkHandler` matches an `ErrorResponse` as a plain `Throwable` first, so `ZHandler.failWith(404, "missing")` reaches the client as status 500 with the body `{"errors":[null]}`. The stream boundary follow-up changes that path.

<!-- Changed by Claude Code on 2026-10-13: validation record for the stream failure and disconnect handling. -->
Validation of the stream failure and disconnect handling used a Linux x86_64 container with the locked Nix shell: JDK 17, Node 24.19.0, and pnpm 10.34.5. Maven Central rate-limited the container again, so sbt and coursier resolved through a Maven Central mirror. The run used `scripts/ci-build.sh`, the same script as CI.

- Frozen pnpm installation: passed.
- Aggregate `pnpm run scala:test`: passed, 17 tests, including the five new boundary tests that run through h3 and `node:http`.
- Development and production linking and both staging checks: passed, including removal of the injected stale development file.
- `pnpm build`: completed, including Nitro bundling.
- Production HTTP smoke test: both tests passed. The failing route answered 200, its body ended with `TypeError: terminated`, and `/api/test` answered `abc` afterwards.
- The build did not modify tracked files.

The finite-stream tests do not establish correct backpressure, stream resource lifetimes beyond interruption, or error redaction. PostgreSQL operations, generated TypeScript DTOs, browser rendering, accessibility, and deployment remain unverified. Those need focused implementation and tests before the MVP can be called ready.
