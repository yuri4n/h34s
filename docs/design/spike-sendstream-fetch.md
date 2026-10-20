# Spike memo: `sendStream` and a Nuxt-style `$fetch`

<!-- claude 2026-10-19: created: title, status and reading guide -->

**Status.** Decision memo for the maintainer, written on 2026-10-19 at the end of the spike of issue [#51](https://github.com/yuri4n/h34s/issues/51). The prototype lives on branch `claude/adoring-carson-016y2z`: four commits on `main` at `f1fb8e1`, head `0f87c7a` before this memo. Nothing here is merged or released. APIs that the prototype does not implement are marked as proposals.

**How to read it.** Section 1 gives the answer. Sections 2 to 4 give the evidence, the feature table and the gap list. Section 5 lists the decisions that only you can make, each with a recommendation. Sections 6 and 7 cover the estimate and the process questions to settle first. Section 8 lists the evidence files.

**Paths.** Library paths are abbreviated:

- `NP` = `node_modules/.pnpm/nitropack@2.13.1_@netlify+blobs@9.1.2_better-sqlite3@12.6.2/node_modules/nitropack/dist`
- `H3` = `node_modules/.pnpm/h3@1.15.4/node_modules/h3/dist/index.mjs`. Nitro itself resolves h3 1.15.5; every function cited here is the same code in both versions.
- `NMH` = `node_modules/.pnpm/node-mock-http@1.0.4/node_modules/node-mock-http/dist/index.mjs`. The file is minified into line 1, so citations name the function.
- `OF` = `node_modules/.pnpm/ofetch@1.5.1/node_modules/ofetch/dist/shared/ofetch.CWycOUEr.mjs`
- `NS` = `node_modules/.pnpm/@nuxt+nitro-server@4.3.1_<hash>/node_modules/@nuxt/nitro-server/dist`
- `kyoh3/`, `zioh3/` and `h3/` are the main source directories of the Kyo core, the ZIO adapter and the native layer under `scala-modules/exts/*/src/main/scala/dev/yuri4n/exts/`. `kyotest/` is `scala-modules/exts/kyo-h3/src/test/scala/dev/yuri4n/exts/kyoh3/`, and `ziotest/` is the matching ZIO test directory.

h34s line numbers refer to the prototype branch unless the citation says `main`.

## 1. Summary and recommendation

<!-- claude 2026-10-19: created: summary and recommendation -->

1. **Proved.** Nitro 2.13.1's in-process local fetch is head-first when the callee gives h3 `sendStream` a Web `ReadableStream`. With the prototype, `KHandler.$fetch.raw` received a Scala route's head after 16-23 ms, before the first element (about 220 ms), and pulled the body on demand.
2. **Proved.** A caller that drops a `Streamed` body cancels the callee when its `Scope` closes, and the cancel waits for the callee's stream and request finalizers.
3. **Refuted.** The documented "opaque 500" for an in-process stream is h34s' own socket guard, not a Nitro or h3 limit. Returning a stream or a Web `Response` does not need h3 v2: h3 1.15 does both. "No `content-length` means streamed" fails, because no in-process response carries `content-length`.
4. **Recommended `sendStream`.** Keep the prototype: Kyo `Response.sendStream(stream): Sent < HandlerEffects`, ZIO `sendStream(stream): ZIO[..., Throwable, Sent]`, and a compile error that names `sendStream` when a handler returns a stream. Write the head when `sendStream` is called, on every transport.
5. **Recommended `$fetch`.** Kyo `KHandler.$fetch` and ZIO `ZHandler.$fetch` with the verbs `raw`, `text`, `as`, `get` and `post`, over Nitro's `event.fetch` (no ofetch retry, throw or buffering). `raw` returns `FetchResponse.Plain` or `FetchResponse.Streamed`, classified by the `x-h34s-stream` marker or a stream content type only. Forward the incoming cookies and `authorization` only to in-process paths.
6. **Next.** Build the typed `$fetch(route)` on PR #15's route registry, and add an SSE helper on top of `sendStream`.

## 2. Measured facts

<!-- claude 2026-10-19: created: measured facts -->

Each fact names the test or command that measured it and the code that explains it. Unless a fact says otherwise, the Kyo tests ran with `sbt --client "kyoH3Ext/testOnly dev.yuri4n.exts.kyoh3.NitroLocalFetchSuite"` (three runs, 10 of 10 green each at `92e9373`; 12 tests at `0f87c7a`). `NitroLocal` (`kyotest/NitroLocal.scala`) copies the wiring of `NP/runtime/internal/app.mjs` and uses nitropack's own h3 1.15.5, node-mock-http 1.0.4 and ofetch 1.5.1. It leaves out route rules, other plugins, async context and the Nitro and Nuxt error handlers. No test ran against `nuxt dev` or a built Nitro server.

### 2.1 Head-first or buffered under Nitro's local call

<!-- claude 2026-10-19: created: head-first measurement -->

- **Code.** `localFetch` goes in-process only when the input string starts with `/`; anything else goes to `globalThis.fetch` (`NP/runtime/internal/app.mjs:100-109`). node-mock-http's `callNodeRequestHandler` awaits the h3 listener, then copies `status`, `headers` and `res._data`; `fetchNodeRequestHandler` wraps that in `new Response(...)` (`NMH:1`, functions `b` and `C`). The local call therefore settles when the callee's h3 handler promise settles.
- **Measured, in-process.** Test "(b) streamed Scala route in-process: $fetch returns head-first; the body is pulled in the caller's Scope" (`kyotest/NitroLocalFetchSuite.scala:123`): `raw` returned after 16-23 ms. The callee's elements arrived at 219-226, 421-428 and 623-630 ms, one per 200 ms sleep. The callee had produced 0 elements when the head arrived.
- **Measured, real HTTP.** Test "(b) the same streamed route over real HTTP: the head leaves with the first chunk" (`kyotest/NitroLocalFetchSuite.scala:160`): the response resolved at 209-222 ms, together with the first element, and ended at 417-433 ms, with `transfer-encoding: chunked` and `x-h34s-stream: 1`. With a socket, the prototype still writes the head lazily before the first chunk, and h3 `sendStream` never calls `flushHeaders` (`H3:855-895`).
- **Buffered callees.** A JS callee that writes with `res.write` and `res.end` is concatenated into one buffer, and the call settles at `end`. Output written after the handler settled is lost (report 01, experiments E7 and E7b; the mock `write` at `NMH:1`, class `l`).
- **Precondition.** The callee must write its head before its h3 promise settles. A head written at the first pull is lost: the caller saw status 200 and no header (report 01, E2). Without a socket, the prototype writes the head first (`kyoh3/internal/StreamResponse.scala:104-133`) and settles the h3 promise early through `HandlerTransport.settleEarly()` (`kyoh3/internal/StreamResponse.scala:116`, `kyoh3/HandlerTransport.scala:38`).
- **Chunk type.** A Web `Response` body accepts only `Uint8Array` chunks: a string chunk makes `Response.text()` throw `TypeError: Received non-Uint8Array chunk` (report 01, E3). The prototype encodes UTF-8 on this path (`kyoh3/internal/StreamResponse.scala:214-216`).

### 2.2 What h3 `sendStream` does under node-mock-http

<!-- claude 2026-10-19: created: sendStream under node-mock-http -->

- **Code.** h3 stores the stream in `event.node.res._data`. When `res.socket` is falsy, it sets `event._handled = true` and returns `Promise.resolve()` without reading the stream (`H3:855-863`). The mock `ServerResponse` has `socket = null` (`NMH:1`, class `w`).
- **Measured.** Test "without a Node socket, the head is written first and the stream is handed over unread, as UTF-8 bytes" (`kyotest/HttpStreamSuite.scala:172`): nothing is written to the response, and `_data` holds the stream. Tests (b) and (c) of `NitroLocalFetchSuite` show that this stream becomes the caller's `Response.body`.
- **With a socket.** h3 pipes a Web stream into `res.write` and resolves after the end (`H3:864-873`). It has no close or abort wiring for Web streams, so h34s keeps its own `close` listener (`kyoh3/internal/RequestLifecycle.scala:20-28` on `main`).
- **Presets.** Many Nitro presets serve every request through `localFetch` or `localCall`, for example Cloudflare (`NP/presets/cloudflare/runtime/cloudflare-worker.mjs:32`), Deno, Bun, Vercel Edge, Netlify and AWS Lambda (report 01, F9). Real clients on those presets take this socketless branch. The node-server preset and the dev worker use a real `node:http` server (`NP/presets/node/runtime/node-server.mjs:16`). Unverified end to end: no preset was built.

### 2.3 What the documented "opaque 500" really is

<!-- claude 2026-10-19: created: the opaque 500 -->

- **Claim.** `docs/streaming.md:73`, `docs/design/kyo-core.md:262` and the published notes (`.github/release-notes/0.2.0-alpha.1.md:86`) say that a stream over Nitro's in-process local fetch is rejected with an opaque 500. `docs/development.md:142` says it was not tested.
- **Cause.** h34s' own guard on `main`: `if socket == null || js.isUndefined(socket) then Abort.fail(UnsupportedOperationException("Streaming requires a Node HTTP response"))` (`kyoh3/internal/StreamResponse.scala:24-26` on `main`). The core renders that non-`SafeError` failure as an opaque 500. Nothing in h3, Nitro or node-mock-http fails.
- **Measured.** The baseline `sbt -batch h3Ext/test kyoH3Ext/test` at `f1fb8e1` passed 36 + 79 tests, including the old Z18 test "without a Node socket, streaming fails before acquiring stream resources". With the guard replaced by the socketless path (commit `dfc8595`), the same fake response streams (`kyotest/HttpStreamSuite.scala:172`), and so does Nitro's real local fetch (`kyotest/NitroLocalFetchSuite.scala:123`).
- **Removing the guard alone is not enough.** Three more problems block it, and the prototype fixes all three: the request scope closed as soon as `sendStream` resolved, the lazy head was lost, and string chunks broke Web consumers (sections 2.1 and 2.4).

### 2.4 The dropped-body cancellation path

<!-- claude 2026-10-19: created: dropped-body cancellation -->

- **Runtime fact.** In-process, nothing signals a disconnect. The mock request never emits `close`, and the mock response's `destroy()` removes its listeners without an event (`NMH:1`). Only `ReadableStream.cancel` on the `Response.body` reaches the callee (report 01, E4). A dropped, unread body is pulled once and never cancelled by the runtime (report 01, E5).
- **Prototype path.** `NitroFetch.raw` registers `Scope.ensure(call.release)` in the caller's scope before it waits for the head (`kyoh3/internal/NitroFetch.scala:29`). `release` cancels the body, or its reader after a partial read, and waits for the cancel (`kyoh3/internal/NitroFetch.scala:122-145`). For an h34s callee, the bridge's `cancel` interrupts the producer and resolves only after the producer and the callee's request scope finished (`afterCancel`, `kyoh3/internal/StreamResponse.scala:109`).
- **Measured.** Tests "(c) a caller that drops a Streamed body: its Scope cancels the callee, whose finalizers run before the caller settles" (`kyotest/NitroLocalFetchSuite.scala:185`) and "(c) a caller that reads part of a Streamed body: the rest is cancelled when its Scope closes" (`:213`). The caller's response arrived only after both callee finalizers ran, each with a 30 ms asynchronous delay (`streamReleased == 1`, `requestReleased == 1`). The dropped stream was pulled exactly once before the cancel.
- **Limits.** A caller that is interrupted before the head arrives cannot stop the callee: node-mock-http takes no abort signal, so the release cancels the body when it arrives, without waiting (`kyoh3/internal/NitroFetch.scala:126-127`). A JS caller or a socketless preset that drops the body without cancelling it keeps the callee's program and request scope open until the body is read or cancelled.

### 2.5 Cookie, header and body forwarding

<!-- claude 2026-10-19: created: forwarding -->

- **Code.** `event.fetch` is `fetchWithEvent(event, req, init, { fetch: localFetch })` (`NP/runtime/internal/app.mjs:61`). `fetchWithEvent` forwards every incoming header except `transfer-encoding`, `accept-encoding`, `connection`, `keep-alive`, `upgrade`, `expect` and `accept`, keeps `host` only for `/` paths, and passes `context: event.context` (`H3:1135-1145`, `H3:1251-1272`).
- **Measured, in-process.** Test "(e) the incoming request's cookies and headers are forwarded as fetchWithEvent does; accept is not" (`kyotest/NitroLocalFetchSuite.scala:274`): the callee saw `cookie` (and `request.cookie("session")`), the bearer token, `x-custom`, `user-agent` and `host: localhost`. It did not see `accept` or `accept-encoding`.
- **Measured, absolute URLs.** Test "an absolute URL also receives the incoming cookie and authorization: fetchWithEvent forwards them to any host" (`kyotest/NitroLocalFetchSuite.scala:318`): a call to `http://127.0.0.1:<port>/...` delivered the user's `cookie` and `authorization` to that server. Nuxt's `event.$fetch` behaves the same way.
- **Stale entity headers.** `fetchWithEvent` also forwards the outer request's `content-type` and `content-length`. Through `event.$fetch`, a JSON POST kept `text/plain` and a wrong length (report 01, E9). The prototype overrides both headers for in-process paths (`kyoh3/internal/NitroFetch.scala:34-39`, `:56-66`). Test "(d) POST body forwarding: post[A] sends JSON with its own content type and length, not the incoming ones" (`kyotest/NitroLocalFetchSuite.scala:237`): the POST carried `application/json` and length 16, and a GET inside an outer `text/plain` POST carried neither header.
- **Body.** node-mock-http stores the request body in `req.body` (`NMH:1`), and h34s' `BodyReader.prebuffered` reads it from there (`h3/internal/BodyReader.scala:59-70`).
- **Context.** The callee can reach the caller's whole `event.context` as `event.node.req.__unenv__` (`NMH:1`; report 01, E9). Nitro copies only `_platform` and `waitUntil` into the callee's own `event.context` (`NP/runtime/internal/app.mjs:49-60`). The prototype passes no context of its own (`kyoh3/internal/NitroFetch.scala:56-66`).
- **Errors.** Test "a callee error status is a typed FetchError.Status for text/as, and a plain status for raw" (`kyotest/NitroLocalFetchSuite.scala:358`): `raw` returned 404 with the callee's body, and `text` failed with `FetchError.Status(404, ...)`. Nothing threw and nothing retried. ofetch, which `event.$fetch` uses, throws on 4xx and 5xx and retries a GET once on 5xx, so a callee ran twice in report 01's E8 (`OF:145-189`, `OF:320-327`).
- **Network failures.** Test "an absolute URL that cannot be reached is a typed FetchError.Failed" (`kyotest/NitroLocalFetchSuite.scala:339`). Before commit `0f87c7a`, the same call ended the Node process with an unhandled rejection.

### 2.6 The proposed detection rule, measured

<!-- claude 2026-10-19: created: detection rule measurement -->

- Test "(a) JSON route in-process: decision 3's proposed rule calls it Streamed (no content-length); as[A] decodes it" (`kyotest/NitroLocalFetchSuite.scala:70`): an in-process JSON response has no `content-length` and no marker, so the proposed rule calls it `Streamed`.
- Test "(a) the same JSON route over real HTTP (absolute URL): content-length is set, so the rule says Plain" (`kyotest/NitroLocalFetchSuite.scala:96`): over HTTP the same response has `content-length: 25`.
- Neither Nitro nor Nuxt marks streamed responses. In-process, no response carries `content-length` or `transfer-encoding` (report 01, F8 and E11). The rule lives in one function, `FetchResponse.isStreamed` (`kyoh3/Fetch.scala:83-86`). The buffering verbs `text`, `as`, `get` and `post` read either kind to the end, so the misclassification does not change their result.

### 2.7 Compile-level facts

<!-- claude 2026-10-19: created: compile-level facts -->

- **Kyo `Env`.** With two `Env` entries in `HandlerEffects`, the documented wiring `KHandler.define(request => Env.run(config)(program(request)))` stops compiling in Kyo 1.0.0-RC7 (`?1.SReduced`, from `kyo.internal.Reducible`). One merged entry, `Env[Fetch & HandlerTransport]`, compiles (`kyoh3/package.scala:13`; probe with `sbt --client kyoH3Ext/Test/compile`).
- **`$fetch` as a name.** Scala 3.9.0 warns `E230` at a definition named `$fetch`. `@annotation.nowarn("id=E230")` at the definition silences it (`kyoh3/KHandler.scala:70`). Call sites such as `KHandler.$fetch.raw(...)` compile without warnings.
- **Name clash.** `kyo.Exchange` exists, and a public `kyoh3.Exchange` resolves to it under `import kyo.*`. The transport type is therefore `HandlerTransport`.
- **Returned streams.** `@implicitNotFound` on `ResponseBody` and `ZResponseBody` (`kyoh3/ResponseBody.scala:18`, `zioh3/ZResponseBody.scala:19`) produces a message that names `sendStream`. Tests: "returning a kyo.Stream does not compile, and the message names sendStream" (`kyotest/HttpStreamSuite.scala:263`) and "returning a ZStream does not compile, and the message names ZHandler.sendStream" (`ziotest/HttpStreamSpec.scala:152`).
- **Whole build.** The CI validate job of `.github/workflows/scala.yml:72-107` was replayed green at `0196dba`, including `scripts/check-consumers.mjs`. After `0f87c7a`, `sbt -batch h3Ext/test kyoH3Ext/test` passed 36 + 96 tests, and `scalafixAll --check` passed for `h3Ext`, `kyoH3Ext`, `zioH3Ext` and `nuxtsvr`.

## 3. Nuxt server features: mechanism, h34s today, proposed shape

<!-- claude 2026-10-19: created: feature table -->

Cost is for one implementer plus review: **S** is up to 1 day, **M** is 1 to 3 days, **L** is more than 3 days. "h34s today" means `main` at `f1fb8e1` unless the cell says "prototype". Every proposed shape is a proposal, not an implemented API.

| #   | Nuxt feature                                                                             | Mechanism in Nitro/h3 (key file)                                                                                                                                                        | h34s today                                                                                                                                                                         | Proposed Scala shape                                                                                                                                 | Cost                               | DX value                                     |
| --- | ---------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------- | -------------------------------------------- |
| 1   | File-based routing (`server/api`, `server/routes`, `[id]`, `[...slug]`, `.get`, `.post`) | Build-time scan into one preemptive h3 router (`NP/core/index.mjs:852-935`, `NP/runtime/internal/app.mjs:117-138`)                                                                      | One hand-written TS re-export per route (`examples/nuxt/server/api/kyo/greeting.get.ts:1`); PR #15 (open) mounts `handler.at(Route.get(...))` through `nitro.handlers`             | PR #15: `KHandler.define(...).at(Route.get("/api/notes/:id"))` in Nitro route syntax, no stub files                                                  | S, 0.5-1 d after PR #15 merges     | High: no stub file per route                 |
| 2   | Middleware (`server/middleware`)                                                         | `h3App.use` layers before the router; a non-`undefined` result ends the chain (`NP/runtime/internal/app.mjs:119-124`, `H3:2004-2025`)                                                   | None: a handler always answers (a `Unit` body is a 204)                                                                                                                            | `KHandler.middleware(request => Unit < MiddlewareEffects)` and `ZHandler.middleware`; `Unit` continues, a `SafeError` stops                          | M, 2-3 d                           | High: shared authentication and logging      |
| 3   | Plugins and runtime hooks (`server/plugins`, `defineNitroPlugin`)                        | Plugins run once at startup, not awaited; hooks `request`, `beforeResponse`, `afterResponse`, `error`, `close` (`NP/runtime/internal/app.mjs:27-90, 156-170`)                           | One h34s Nitro plugin: request record and `close` (`packages/nuxt/src/runtime/context.mjs:37-56`); `Shared` and `ZShared` for application resources                                | Keep `Shared`/`ZShared`; add an error-report hook that also sees h34s defects (h34s renders them itself, so Nitro's `error` hook does not; inferred) | M, 2-3 d                           | Medium: error reporting                      |
| 4   | Auto-imports and `server/utils`                                                          | unimport rewrites JS and TS sources at build time (`NP/core/index.mjs:295-376`, `NP/rollup/index.mjs:1849-1852`)                                                                        | One prelude import per runtime, `dev.yuri4n.exts.kyoh3.*` or `dev.yuri4n.exts.zioh3.*` (`kyoh3/package.scala:17-41`); utils are ordinary Scala objects                             | Keep one import; export every new public name (`Sent`, `Fetch*`, `Route`) from it                                                                    | S, under 0.5 d                     | Medium: already close                        |
| 5   | Typed `$fetch` (route types)                                                             | `writeTypes` builds `InternalApi` from each handler's return type (`NP/core/index.mjs:1168-1260`, `NP/types/index.d.ts:120-173`)                                                        | None in Scala; PR #15 writes a `.d.ts` per Scala route for TypeScript callers only                                                                                                 | `val notes = Route.get[List[Note]]("/api/notes")`, then `KHandler.$fetch(notes)` gives `List[Note]` with the callee's envelope removed               | L, 3-5 d; needs PR #15             | High: calls checked at compile time          |
| 6   | Server-side `$fetch` (`event.$fetch`: in-process, forwards the user's headers)           | `fetchWithEvent` over `localFetch`; `/` paths in-process (`NP/runtime/internal/app.mjs:61-64, 100-109`, `H3:1251-1272`)                                                                 | Removed in 0.2.0 (`docs/migration.md:88`). Prototype: Kyo `KHandler.$fetch` over `event.fetch`                                                                                     | `KHandler.$fetch.get[A]("/api/x")`; `.raw` gives `FetchResponse.Plain` or `Streamed`; ZIO `ZHandler.$fetch` with the same verbs                      | Kyo S-M, 2-2.5 d; ZIO M, 1.5-2 d   | High: every route, with the user's identity  |
| 7   | `useRuntimeConfig(event)`                                                                | Inlined config plus `NITRO_`/`NUXT_` overrides, cloned per request (`NP/runtime/internal/config.mjs:1-36`)                                                                              | `RuntimeConfig` and `ZRuntimeConfig` over the plugin's request record (`packages/nuxt/src/runtime/context.mjs:31-56`)                                                              | No change                                                                                                                                            | None                               | Done                                         |
| 8   | `useStorage`                                                                             | unstorage mounts in a virtual module (`NP/runtime/internal/storage.mjs:1-5`, `NP/rollup/index.mjs:1580-1632`)                                                                           | `storage(base)` in the plugin's records (`packages/nuxt/src/runtime/context.mjs:39, 55`) and the NuxtHub KV bridges                                                                | No change                                                                                                                                            | None                               | Done                                         |
| 9   | `defineCachedEventHandler`, `defineCachedFunction`                                       | Wrapper over `useStorage`; stores the returned body or the last string written, never a status of 400 or more (`NP/runtime/internal/cache.mjs:147-161, 204-227`)                        | No API. h34s writes the head and returns value bodies to h3 (`kyoh3/KHandler.scala:165-171` on `main`), so a `cache` route rule may work: untested. A sent stream cannot be cached | `KHandler.cached(CacheOptions(maxAge = 60.seconds))(handler)` over Nitro's helper, values only                                                       | M, 1.5-2.5 d                       | Medium                                       |
| 10  | `defineTask` (experimental in Nitro)                                                     | Scanned `server/tasks`, `runTask`, cron schedules (`NP/core/index.mjs:941-947`, `NP/runtime/internal/task.mjs:5-73`)                                                                    | None                                                                                                                                                                               | `KTask.define(payload => result < TaskEffects)`; application resources only, no request                                                              | M, 1.5-2.5 d                       | Low-medium: experimental                     |
| 11  | `defineWebSocketHandler` (experimental in Nitro)                                         | h3 handler with `__websocket__` plus crossws on `upgrade` (`H3:1735-1748`, `NP/presets/node/runtime/node-server.mjs:37-40`)                                                             | None                                                                                                                                                                               | Incoming messages as a stream, outgoing messages as a stream, close as interruption                                                                  | L, 4-6 d                           | Low: decision 5 prefers SSE                  |
| 12  | `createError` and the error page                                                         | `toNodeListener` catches, then Nitro's and Nuxt's error handlers; `/api/*` gets JSON (`H3:2292-2319`, `NP/runtime/internal/error/prod.mjs:17-68`, `NS/runtime/handlers/error.mjs:6-77`) | `KHandler.failWith`, `ZHandler.failWith` and `SafeError`, rendered by h34s in its envelope; any other failure is an opaque 500, with diagnostics only in development or debug mode | No change                                                                                                                                            | None                               | Done                                         |
| 13  | Route rules (`headers`, `redirect`, `proxy`, `cors`, `cache`, `swr`)                     | The first app layer, before middleware and routes (`NP/runtime/internal/route-rules.mjs:15-67`, `NP/core/index.mjs:496-549`)                                                            | Applied before h34s code runs; no test covers a rule on an h34s route                                                                                                              | No Scala API; one test per rule kind and a guide section                                                                                             | S, 0.5-1 d                         | Medium: users expect declarative rules       |
| 14  | `handleCors`                                                                             | Runtime helper; answers a preflight with 204 (`H3:991-1102`)                                                                                                                            | None; the `cors: true` route rule runs before h34s (untested)                                                                                                                      | Document the route rule; add a handler option only if a per-handler need appears                                                                     | S, 0.5 d                           | Medium                                       |
| 15  | `sendRedirect`                                                                           | Status plus `location` and a tiny HTML body (`H3:774-783`)                                                                                                                              | `Response.redirect(location, status)` in both runtimes (`kyoh3/Response.scala:43` on `main`)                                                                                       | No change                                                                                                                                            | None                               | Done                                         |
| 16  | `proxyRequest`, `sendProxy`                                                              | Forwards headers and body, copies status and headers, writes the body chunk by chunk (`H3:1146-1250`)                                                                                   | None; a `proxy` route rule works before h34s                                                                                                                                       | `Response.send(fetchResponse)`: pass a `$fetch.raw` result through and give `Sent`                                                                   | S-M, 1-1.5 d; needs `$fetch`       | Medium                                       |
| 17  | `createEventStream` (SSE)                                                                | A `TransformStream` of SSE frames sent with `sendStream`, SSE headers, status 200 (`H3:1474-1642`)                                                                                      | None. Prototype: possible by hand with `sendStream` and a `text/event-stream` header                                                                                               | `Response.sendEvents(events: Stream[ServerEvent, S]): Sent < HandlerEffects`; caller side `KHandler.$fetch.events[E]`                                | M, 1-1.5 d                         | High: the preferred server-to-client channel |
| 18  | `setResponseStatus`, response headers, `setCookie`                                       | Mutate the Node response at once (`H3:602-625, 752-834`)                                                                                                                                | `Response.status`, `header`, `appendHeader`, `cookie`, `deleteCookie` on `Var`/`ZState`, written once (`kyoh3/Response.scala:13-62` on `main`)                                     | No change                                                                                                                                            | None                               | Done                                         |
| 19  | Return contract: an object gives JSON 200                                                | `JSON.stringify` with `application/json` (`H3:2115-2117`)                                                                                                                               | Any `NativeConverter` value: `{ "data": ... }` by default, raw JSON with `SuccessEnvelope.Raw`                                                                                     | No change                                                                                                                                            | None                               | Done                                         |
| 20  | Return contract: a string gives text 200                                                 | h3 1.15 sends a string as `text/html`, not `text/plain` (`H3:2112-2114`)                                                                                                                | A `String` with `Raw` is `text/plain; charset=utf-8` (`kyoh3/internal/Rendering.scala:40-54` on `main`); with `Data` it is `{ "data": "..." }`                                     | No change                                                                                                                                            | None                               | Done; stricter than h3                       |
| 21  | Return contract: `null` gives 204                                                        | `sendNoContent` (`H3:2086-2088`); `undefined` from a Nitro route also gives 204 (`H3:2243-2247`)                                                                                        | A `Unit` body gives 204 unless the program set a status                                                                                                                            | No change                                                                                                                                            | None                               | Done                                         |
| 22  | Return contract: a stream is streamed                                                    | `sendStream` (`H3:2093-2094`); h3 1.15 already does this, not only h3 v2                                                                                                                | A returned `kyo.Stream` or `ZStream`. Prototype: `Response.sendStream` and `ZHandler.sendStream` give `Sent`; a returned stream does not compile                                   | As prototyped (decision 1)                                                                                                                           | S, 0.5-1 d code; docs in section 6 | High: explicit, head-first in-process        |
| 23  | Return contract: a Web `Response` passes through                                         | `sendWebResponse` copies status and headers, then calls `sendStream` (`H3:2090-2091, 940-965`); h3 1.15 already does this                                                               | None                                                                                                                                                                               | Same as row 16: `Response.send(fetchResponse)` gives `Sent`                                                                                          | In row 16                          | Medium                                       |
| 24  | Return contract: `throw createError(...)` gives an error                                 | Caught by `toNodeListener`, then the error handlers (`H3:2292-2319`)                                                                                                                    | Kyo `Abort.fail(HttpError(...))` or `KHandler.failWith`; ZIO `ZIO.fail(ErrorResponse(...))` or `ZHandler.failWith`; a thrown exception is a defect, an opaque 500                  | No change                                                                                                                                            | None                               | Done                                         |

## 4. Top 10 gaps

<!-- claude 2026-10-19: created: top 10 gaps -->

The ranking divides DX value (high 3, medium 2, low 1) by cost (S 1, M 2, L 3, S-M 1.5). Ties follow the dependency order. Rows marked "Done" in section 3 are not gaps.

1. **Finish `sendStream` in both runtimes.** Shape: as prototyped, with the head written at the call (Q1) and a failure after the head that fails the step (Q3). Score 3. Depends on decisions Q1, Q3 and Q8.
2. **Merge PR #15 and give `Sent` a wire shape.** Shape: `handler.at(Route.get(...))`; a route that ends in `sendStream` gets the TypeScript type `string`. Score 3. Depends on gap 1 and on your review of PR #15.
3. **Settle the Kyo `$fetch` policies.** Shape: `KHandler.$fetch` as prototyped, plus the detection rule (Q2), credential scope and `app.baseURL` (Q5, Q6), a caller-owned context, typed body errors and `headerValues` (Q14). Score 2. Depends on the decisions only.
4. **Public test helper.** Shape: `KHandler.test(handler)(request)` and `ZHandler.test`, which provide a detached transport and a fake `Fetch` and return the head and the body (Q10). Score 2. Depends on gaps 1 and 3.
5. **Route rules and CORS on h34s routes.** Shape: no API; tests for `headers`, `cors`, `redirect` and `cache` on an h34s route, and a guide section. Score 2. Independent.
6. **ZIO `$fetch`.** Shape: `ZHandler.$fetch` with the Kyo verbs, a `ZStream` body for `Streamed`, and a request-`Scope` finalizer that cancels an unread body (Q8). Score 1.5. Depends on gap 3, because it reuses `NitroFetch`'s request building.
7. **SSE helper.** Shape: `Response.sendEvents` and the ZIO twin over `sendStream`, plus `$fetch.events[E]` for typed events (decision 4, Q7). Score 1.5. Depends on gaps 1 and 3.
8. **Middleware.** Shape: `KHandler.middleware` and `ZHandler.middleware`; `Unit` continues, a `SafeError` stops with the error rule. Score 1.5. Depends on gap 2 for mounting.
9. **Pass-through and proxy.** Shape: `Response.send(fetchResponse)` gives `Sent`; a proxy is `$fetch.raw` followed by `Response.send`. Score 1.3. Depends on gaps 1 and 3.
10. **Typed route registry.** Shape: `Route.get[A](path)` and `KHandler.$fetch(route)`, with the callee's envelope applied. Score 1. Depends on gaps 2, 3 and 6.

Not in the top 10: cached handlers (M, medium), a plugin error-report hook (M, medium), tasks (M, low-medium) and websockets (L, low).

## 5. Decisions still open

<!-- claude 2026-10-19: created: open decisions -->

**Q1. When does `sendStream` write the head over a socket?**

Option A: write the head and call `flushHeaders()` when `sendStream` is called, on every transport. Option B: keep the prototype's split, which writes the head lazily before the first chunk with a socket and eagerly without one.

_Recommendation: A._ One rule then holds for every transport and every preset; socketless presets already give real clients the eager head (section 2.2). SSE and progress clients get the head at once, as decision 3 asks. The cost: a stream failure before the first element can only end the stream, not change the status. Tell application authors to acquire resources and validate input before they call `sendStream`. The test "a failure before the first chunk is rendered with the failure rule" (`kyotest/HttpStreamSuite.scala:119`) changes.

**Q2. Which rule decides `Streamed` or `Plain`?**

Option A: the `x-h34s-stream` marker only. Option B: the marker, or a stream content type (`text/event-stream`, `application/x-ndjson`). Option C: B plus "no `content-length`" (the proposal). Option D: in-process, the body type that `nitroApp.localCall` returns (`string` or `ReadableStream`); over the network, the headers. Option E: a registry, built from PR #15's metadata, of the routes that stream.

_Recommendation: B._ It is exact for every h34s callee on both paths, covers h3's `createEventStream`, and matches what Nuxt's own ofetch streams (`text/event-stream`, `OF:82-97`). C calls every in-process response streamed (section 2.6). E misses JS routes. The consequence of B: a JS route that streams with plain `sendStream` and no content type is `Plain`, so `raw` reads it to the end and never returns for an endless stream. D removes that case for about one day of work (h34s rebuilds `fetchWithEvent`'s forwarding over `localCall`); revisit it when such a route appears.

**Q3. What happens when the stream fails after the head?**

Option A: `sendStream` still gives `Sent` and the core logs the failure (prototype). Option B: `sendStream` fails with the stream's error; the core logs it and writes nothing more.

_Recommendation: B._ A transaction or a `Scope` finalizer around the stream then sees the failure and can roll back, as the disconnect guidance already describes for interrupted work. It needs one test per runtime.

**Q4. Should a Scala-to-Scala call use Nitro's local call or call the handler program directly?**

Option A: the local call (prototype). It runs the whole Nitro app: route rules (`NP/runtime/internal/app.mjs:116`), middleware (`:119-124`), the `request`, `beforeResponse` and `afterResponse` hooks of plugins (`:47-90`), the error handler (`:43-46`) and the router's parameters. It costs a mock request and response and a JSON round trip; that overhead was not measured. Option B: a direct in-process call of the handler program. It is typed and skips serialization, but it bypasses route rules, middleware (authentication included), plugin hooks and the error handler, and it needs a separate `Request` snapshot.

_Recommendation: A for `$fetch`._ To reuse logic without HTTP semantics, call your service directly, as the migration guide already advises.

**Q5. What does `$fetch` do for a route that is not in the app?**

Facts: a `/` path without a route gets Nitro's 404, in-process. An absolute URL or a `Request` object goes over the network through `globalThis.fetch` (`NP/runtime/internal/app.mjs:100-109`), and `fetchWithEvent` forwards the user's `cookie` and `authorization` to that host (section 2.5). Option A: Nuxt's behavior, which forwards everything to any host. Option B: forward the incoming headers only for `/` paths; an absolute URL gets only the headers the caller passes. Option C: an allow-list of hosts.

_Recommendation: B._ In-process calls keep the Nuxt experience, and a user's session never reaches a third party by accident. Document this difference from `event.$fetch`.

**Q6. Which Nitro primitive does `$fetch` wrap?**

Option A: `event.fetch` (prototype): a raw `Response`, no retry, no throw, no buffering, and no `app.baseURL`. Option B: `event.$fetch` (ofetch): it applies `app.baseURL`, but it buffers, throws on 4xx and 5xx, and retries a GET on 5xx, so a callee can run twice. Option C: `useNitroApp().localCall`: the exact body type, but no forwarding.

_Recommendation: A_, with two additions: prefix `app.baseURL` in h34s, and pass a context that holds only `_platform` and `waitUntil`, so the callee cannot reach the caller's whole `event.context` (section 2.5).

**Q7. Does `Sent` also cover SSE through h3's `createEventStream`?**

Option A: wrap h3's `createEventStream`. In-process it leaks two unhandled rejections per cancel and detects a disconnect only through the request's `close` event, which never fires there (`H3:1529-1534, 1570, 1585`; report 01, E6). Option B: an h34s helper on top of `sendStream`: `Response.sendEvents(events: Stream[ServerEvent, S]): Sent < HandlerEffects` and its ZIO twin, with h3's SSE headers and the same bridge, scope and cancellation rules. The caller side is `$fetch.events[E]`, which decodes frames into typed events (decision 4).

_Recommendation: B._ `Sent` covers it, because it is a `sendStream`.

**Q8. Which shape does the ZIO side take?**

The prototype has `ZHandler.sendStream[E <: Throwable](stream: ZStream[Any, E, String]): ZIO[ZState[ResponseHead] & HandlerTransport, Throwable, Sent]` (`zioh3/ZHandler.scala:59`) and `HandlerEnv = ZState[ResponseHead] & Scope & HandlerTransport` (`zioh3/package.scala:17`). The proposed ZIO `$fetch` adds a `ZFetch` service to `HandlerEnv`, and `ZHandler.$fetch.raw` returns `ZIO[HandlerEnv, FetchError, ZFetchResponse]`, whose `Streamed.body` is a `ZStream` and whose unread body is cancelled by a request-`Scope` finalizer. Naming: Kyo uses `Response.sendStream`, ZIO uses `ZHandler.sendStream`, and `zioh3.Response` also exists. Option A: keep the asymmetric names of decision 1. Option B: `Response.sendStream` in both runtimes. Option C: `KHandler.sendStream` and `ZHandler.sendStream`.

_Recommendation: B._ One name per concept, next to the other head operations. Both names need a migration row, because `0.1.0-alpha.1` had a `ZHandler.sendStream` with another signature.

**Q9. Which shape does `HandlerEffects` take?**

Option A: one merged `Env[Fetch & HandlerTransport]` (prototype; Kyo RC7 rejects two separate entries with the documented `Env.run` wiring, section 2.7). Option B: a Kyo `Local` for the transport, and only `Env[Fetch]` in the alias. The type then does not say that a handler can stream, and `sendStream` fails at run time outside a handler.

_Recommendation: A_, together with the test helper of Q10, so that tests never build the environment by hand. Every future per-request service joins the same `Env` intersection.

**Q10. How do tests call a handler function directly?**

Today `docs/testing.md:52-99` builds the environment by hand. With the prototype, a direct call must also provide `HandlerTransport.detached` (`kyoh3/HandlerTransport.scala:48`) and a `Fetch` such as `Fetch.unavailable`. Option A: document the manual provisioning. Option B: a public `KHandler.test` and `ZHandler.test` that provide a detached transport and a fake or recording `Fetch`, and return the head and the body.

_Recommendation: B._

**Q11. Keep the name `$fetch`?**

`$fetch` needs `@annotation.nowarn("id=E230")` at its definition (`kyoh3/KHandler.scala:70`); call sites compile without warnings. Option A: keep `$fetch`. Option B: rename it to `fetch`.

_Recommendation: A._ Nuxt users recognize it, and the cost stays inside h34s.

**Q12. What happens to a socketless stream that nobody reads or cancels?**

It keeps the callee's program and request scope open (section 2.4). `KHandler.$fetch` callers are safe; JS callers and dropped bodies on socketless presets are not. Option A: document it. Option B: an idle timeout in `HandlerOptions`. Option C: both.

_Recommendation: A now_, and a tracked issue for B once a preset test exists.

**Q13. Which Nitro presets does h34s support for streaming?**

Socketless presets now stream for real clients, but no disconnect reaches h34s unless the platform cancels the body. That is unverified.

_Recommendation:_ list only node-server and `nuxt dev` as tested in `docs/compatibility.md`, and keep issue [#36](https://github.com/yuri4n/h34s/issues/36) open for the other presets.

**Q14. Should `Streamed.body` carry typed errors, and should headers keep repeated values?**

Decision 3 fixed `Stream[String, Async & Scope]`, so a callee failure in the middle of the stream reaches the caller as a panic. `FetchResponse.headers` joins repeated values, `set-cookie` included.

_Recommendation:_ add `Abort[FetchError]` to the body type, and add `headerValues(name)` as `Request` already has.

**Q15. Should the untyped verbs remove the callee's envelope?**

`$fetch.get[A]` decodes the raw JSON, and an h34s callee answers `{ "data": ... }` by default.

_Recommendation:_ keep the untyped verbs raw, and let the typed route (gap 10) apply the callee's envelope, because only the route knows the callee's `HandlerOptions`.

**Q16. Should a caller's cancel wait for the callee's whole request cleanup?**

The prototype's cancel resolves after the callee's request scope closed, so a slow callee finalizer delays the caller.

_Recommendation: keep it._ It mirrors the disconnect rule: finalizers first, then settle.

**Q17. Which h3 copy does a production bundle use?**

The app pins h3 1.15.4 and nitropack resolves 1.15.5. The cited functions behave the same in both. This is unverified for a built bundle.

_Recommendation:_ check it in the first `nuxt build` of the implementation round; no decision is needed unless the copies differ.

## 6. Estimate for the implementation round

<!-- claude 2026-10-19: created: estimate -->

The shape is one implementer, one reviewer and one fixer. Days are working days of one agent or person.

| Item                                   | Implementer       | Reviewer        | Fixer      | Assumptions                                                                                                                                               |
| -------------------------------------- | ----------------- | --------------- | ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Kyo `sendStream`                       | 0.5-1 d           | 0.25 d          | 0.25 d     | Q1 and Q3 decided; commit `dfc8595` is the base                                                                                                           |
| ZIO `sendStream`                       | 0.5 d             | 0.25 d          | 0.25 d     | Follows the Kyo change; adds a `NitroLocal` test for ZIO, which needs `H34S_WORKSPACE` in the `zioH3Ext` test environment                                 |
| Kyo `$fetch`                           | 2-2.5 d           | 0.5 d           | 0.5 d      | Q2, Q5, Q6, Q9 to Q11 and Q14 decided; includes the public test helper                                                                                    |
| ZIO `$fetch`                           | 1.5-2 d           | 0.5 d           | 0.5 d      | Reuses `NitroFetch`'s request building; tests mirror `NitroLocalFetchSuite`                                                                               |
| Typed route registry with PR #15       | 2.5-3.5 d         | 0.5-1 d         | 0.5 d      | PR #15 merged first; includes 0.5 d to rebase the prototype onto it (`KHandler.scala`, `ResponseBody.scala`, `ZResponseBody.scala` conflict)              |
| SSE helper                             | 1 d               | 0.25 d          | 0.25 d     | Only if Q7 is B                                                                                                                                           |
| `docs/streaming.md`                    | 0.5 d             | shared          | shared     | Send instead of return; in-process behavior and its tests; the "opaque 500" row goes                                                                      |
| `docs/migration.md`                    | 0.5 d             | shared          | shared     | A new upgrade section; `sendStream` and `$fetch` return with new signatures, so the guide says so explicitly                                              |
| `docs/kyo.md` and `docs/zio.md`        | 0.5-1 d           | shared          | shared     | The ZIO quickstart (`docs/zio.md:29`) becomes `ZHandler.sendStream`; the body tables lose the stream rows; the `HandlerEffects`/`HandlerEnv` text changes |
| Design record and `AGENTS.md`          | 0.5 d             | shared          | shared     | New decision items, sections 4.2, 4.4 and 8, deviation 9.7 (dev. 28 is superseded), appendix facts; the rule wording of section 7                         |
| Release notes                          | 0.25 d            | shared          | shared     | One breaking-change entry that reverses `0.2.0-alpha.1.md:60` and `:86`                                                                                   |
| Website mirror and other pages         | 0.5-1 d           | shared          | shared     | Hand-condensed copies under `website/content/`; `docs/testing.md`, `docs/architecture.md`, `docs/compatibility.md`                                        |
| Documentation review and fixes (total) |                   | 0.5 d           | 0.5 d      |                                                                                                                                                           |
| **Total**                              | **10.75-14.25 d** | **2.75-3.25 d** | **2.75 d** |                                                                                                                                                           |

Assumptions for the whole round: you settle the decisions of section 5 before it starts; PR #15 merges first; one sbt process runs at a time; a full CI replay takes about 6 minutes in a cloud session (measured at `0196dba`: 99 s of sbt tests, then `scala:full`, the release checks and 156 s of `check-consumers`). The estimate leaves out the preset matrix (it needs a built app per preset; size unknown) and the features below the top 10.

## 7. Process notes to settle before the implementation round

<!-- claude 2026-10-19: created: process notes -->

1. **Delivery.** The handoff says the round is "pushed straight to main after the full check (the user does not want to review PRs)". `AGENTS.md` ("Change discipline and delivery workflow") requires focused pull requests and forbids merging without explicit authorization. Five pull requests are open and waiting for your review: [#15](https://github.com/yuri4n/h34s/pull/15), [#45](https://github.com/yuri4n/h34s/pull/45), [#47](https://github.com/yuri4n/h34s/pull/47), [#48](https://github.com/yuri4n/h34s/pull/48) and [#49](https://github.com/yuri4n/h34s/pull/49). Record which rule applies in `AGENTS.md` before the round starts.
2. **Version.** The handoff names the next release `0.2.0-alpha.2`. `version.sbt:1` says `0.2.0-alpha.1`. GitHub milestone #1 is `0.3.0-alpha.1` with a provisional scope, and PRs #47, #48 and #49 also use `0.3.0-alpha.1`. Decision 1 is a breaking change, and the only precedent (`0.1.0-alpha.1` to `0.2.0-alpha.1`) raised the minor version for breaking changes. Pick one name before anyone writes release notes.
3. **Rule wording.** `sendStream` writes the native response while the program runs, which the recorded rules forbid. These sentences need new text:
   - `AGENTS.md:39`, now: "A handler receives an immutable `Request` snapshot and returns the response body." Proposed: "A handler receives an immutable `Request` snapshot and returns the response body, or sends a stream with `sendStream` and returns the `Sent` value it gives."
   - `AGENTS.md:40`, now: "h34s writes the head and body once, after the program and its finalizers finish. No public API mutates the native response while the program runs." Proposed: "For a value or `Unit` body, h34s writes the head and body once, after the program and its finalizers finish. A streaming handler ends with `sendStream`, which writes the head as it stands at the call and then the elements; the request scope stays open until the stream ends or is cancelled. No other public API writes the native response while the program runs."
   - `docs/design/kyo-core.md:147`, now: "h34s writes the head and the body once, after the program and its finalizers finish. A streamed body writes the head just before the first chunk." Proposed: the `AGENTS.md:40` text above, plus the head timing that Q1 selects.
   - `docs/design/kyo-core.md:128`, now: "...any type with a `NativeConverter`, `Unit`, or a stream of strings. There is no `Env[Request]` and no method that mutates the native response while the program runs." Proposed: "...any type with a `NativeConverter`, `Unit`, or `Sent`. There is no `Env[Request]`, and `sendStream` is the only method that writes the native response while the program runs."
   - `AGENTS.md:51` keeps its rule; "until the stream value is returned" becomes "until `sendStream` is called".
4. **PR #15 is the base for the typed `$fetch`.** It edits the same files as the prototype (`kyoh3/KHandler.scala`, `kyoh3/ResponseBody.scala`, `zioh3/ZResponseBody.scala`, both `package.scala` files), and decision 1 removes its only source of `BodyShape.Text`. Start the round after PR #15 merges, or rebase the round on it.
5. **Design-record numbering.** On `main` the next decision is item 15. PR #47 adds items 15 to 26 under a `2026-10-19` heading, so a branch cut from `main` collides with it. Issue #51 holds decisions 1 to 6 as text. Merge PR #47 first, or number the new items after it.
6. **Release-notes file.** PRs #45, #48 and #49 each create `.github/release-notes/unreleased.md`, which `main` does not have; merging the second and third is an add/add conflict. Name one owner for that file.
7. **Commit attribution.** The workflow brief asked for a `Claude Fable 5.1` co-author trailer. The session's attribution instruction names `Claude Opus 5.5`, and the prototype commits and this memo's commit use it, with the same `Claude-Session` line.

## 8. Evidence appendix

<!-- claude 2026-10-19: created: evidence appendix -->

The reports live in the session scratch directory, which can disappear. This memo repeats every fact that a decision depends on.

- `/tmp/claude-0/-home-user-h34s/526a028b-0f90-59b0-bf76-773e7169b572/scratchpad/spike/01-nitro-runtime.md`: Nitro's local fetch, node-mock-http, `fetchWithEvent`, ofetch, errors, `createEventStream`, disconnects and detection signals, with experiments E1 to E13 (scripts and outputs in `spike/exp/`).
- `.../spike/02-nitro-features.md`: the mechanism of every Nuxt server feature of decision 5, with the h3 1.15 return contract.
- `.../spike/03-h34s-today.md`: the h34s code on `main` that the prototype changes, PR #15's registry, the test harnesses and the commands.
- `.../spike/04-docs-claims.md`: every guide, design-record and release-notes line that decisions 1 to 4 contradict, and the versioning state.
- `.../spike/05-prototype.md`: what the prototype does and does not do, measured facts M0 to M15, the verification log and the estimate inputs.
- `.../spike/06-memo.md`: this memo's own log.
- Logs: `spike/baseline.log` (baseline at `f1fb8e1`), `spike/ci/` (the CI replay at `0196dba`), `spike/unfixed.log` and `spike/fixed.log` (the network-failure fix), `spike/scalafix.log`.
- Prototype branch `claude/adoring-carson-016y2z`, head `0f87c7a` before this memo: `dfc8595` (Kyo `sendStream`), `92e9373` (Kyo `$fetch` and `NitroLocalFetchSuite`), `0196dba` (ZIO `sendStream`), `0f87c7a` (`$fetch` network failures and forwarding to absolute URLs).
- Tracker: issue [#51](https://github.com/yuri4n/h34s/issues/51) (the spike and decisions 1 to 6) and issue [#36](https://github.com/yuri4n/h34s/issues/36) (streaming limits).
