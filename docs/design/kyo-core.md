# Kyo core: design record

This page records why h34s is built the way it is. It covers the decisions the user made, the design those decisions led to, every place where the implementation differs from the first plan, and the library facts that the code depends on. The guides in `docs/` tell you how to use h34s; this record tells you why it works that way, so you can change it without breaking a promise.

**Status.** It describes `main` at release `0.2.0-alpha.1` (merge commit `433ac9d`, published on 2026-10-18 as the [`v0.2.0-alpha.1`](https://github.com/yuri4n/h34s/releases/tag/v0.2.0-alpha.1) and [`npm-v0.2.0-alpha.1`](https://github.com/yuri4n/h34s/releases/tag/npm-v0.2.0-alpha.1) prereleases). Remaining work and open questions live in the tracking issue [#17](https://github.com/yuri4n/h34s/issues/17), not here.

**Where it comes from.** The Kyo-core refactor (PR [#14](https://github.com/yuri4n/h34s/pull/14), 2026-10-17 and 2026-10-18) kept its design, research, progress and review notes outside the repository. This page is the curated, durable copy of those notes.

**Keep it current.** When you change one of these decisions or behaviors, update this page in the same pull request. When the user makes a new decision, also record it in `AGENTS.md`.

**Decision records.** From 2026-10-20 on, decisions are also recorded as architecture decision records (ADRs) under [`docs/design/adr/`](adr/README.md), one decision per record, with the options that were considered and the reasons. This page keeps the narrative and the numbered decision items, and links to the ADRs. [ADR-0001](adr/0001-delivery-process.md) replaces the merge rule of section 1, "Recorded before the refactor": the orchestrating session merges green pull requests, and force-pushes and publication still need the user's explicit approval. Five of the records touch the Kyo core and the ZIO adapter: [ADR-0004](adr/0004-streams-are-sent.md) (streams are sent with `Response.sendStream`), [ADR-0005](adr/0005-fetch-in-handlers.md) (`$fetch` in handlers), [ADR-0006](adr/0006-handler-environment-and-test-helpers.md) (the handler environment and the test helpers), [ADR-0007](adr/0007-server-sent-events.md) (server-sent events) and [ADR-0008](adr/0008-streaming-support-limits.md) (streaming support limits). ADR-0004 and ADR-0006 are implemented (items 27 and 28 below); the Kyo `$fetch` of the spike is in the code with two of the policies of ADR-0005 (items 29 and 30), and the streaming guide documents the unread socketless stream of ADR-0008. ADR-0007 is not implemented yet. <!-- claude 2026-10-20: created: pointer to the ADRs under docs/design/adr/ and the ADR-0001 merge rule --> <!-- claude 2026-10-20: changed: ADR-0004 and ADR-0006 are implemented; two ADR-0005 policies are implemented -->

Tags such as *(dev. 4)*, *(B2-1)* and *(D-1)* are the deviation numbers from the original design notes. Review IDs such as R3, SEC-4, AD-9 and B1 are the findings of the adversarial review of PR #14; some test comments use them.

## Contents

1. [Decisions from the user](#1-decisions-from-the-user)
2. [Module graph and dependency direction](#2-module-graph-and-dependency-direction)
3. [Layers](#3-layers)
4. [The HTTP model](#4-the-http-model)
5. [Configuration and application resources](#5-configuration-and-application-resources)
6. [Schema strategy](#6-schema-strategy)
7. [The ZIO bridge](#7-the-zio-bridge)
8. [Streaming](#8-streaming)
9. [Implementation deviations](#9-implementation-deviations)
10. [Review findings and their outcome](#10-review-findings-and-their-outcome)
11. [Appendix: facts from research that still hold](#appendix-facts-from-research-that-still-hold)

## 1. Decisions from the user

These are binding. The lasting ones are also in `AGENTS.md`; the release steps are recorded here for history.

### Recorded before the refactor

<!-- claude 2026-10-20: changed: docs/ holds the guides and the site (issue #25) -->

- The project is named **h34s**. It is a toolbox: the application owns its models, services, databases and deployment. The repository root holds reusable libraries; `examples/nuxt/` is a runnable demo and `docs/` holds the guides and the documentation site. Neither is the product.
- The repository [yuri4n/h34s](https://github.com/yuri4n/h34s) stays private. Releases go to private GitHub Packages (Maven and npm) under `dev.yuri4n`, starting with `0.1.0-alpha.1`, with a matching Git tag and GitHub prerelease.
- Developer experience is the highest priority, next to type safety and efficiency.
- The user chose current Kyo (1.0.0-RC7) with the tooling updates it needs over an older Kyo that would have kept the old compiler pin.
- The ZIO database example uses **Skunk** inside Scala.js and Nitro. The user reconfirmed Skunk after discussing Doobie, because Doobie needs a JVM backend.
- `yurian.land` uses the first-class Kyo API.
- Scala-to-TypeScript generation was planned with **scala-ts**. PR [#15](https://github.com/yuri4n/h34s/pull/15) proposes an h34s generator instead and asks the user to decide.
- sbt with Nix, and sbt-native-packager, are first-class targets. Interactive documentation (type hovers, symbol links, runnable examples) is an explicit post-MVP goal.
- Work goes through branches, small commits and pull requests. Nothing is merged, force-pushed or published without the user's explicit approval.

### 2026-10-17: the Kyo core

1. **Kyo is the core and the lingua franca.** The h3 transport, request lifecycle, cancellation, streaming, error rendering, configuration access and application-scoped resources are implemented once, in Kyo. The user first asked for a ZIO core, then corrected it to the opposite: a Kyo core with hand-written ZIO adapters, bindings and support libraries.
2. **ZIO is a first-class, hand-written adapter over the Kyo core.** ZIO users depend on Kyo transitively. Kyo users never depend on ZIO. This replaced two older rules: "Kyo-only and ZIO-only consumers need neither of the other runtime" and, in yurian.land, "no ZIO runtime beneath Kyo handlers".
3. **Application authors write ordinary Scala.** No `js.native`, `js.Promise`, `js.Dynamic`, facades, casts or TypeScript glue in normal use. JavaScript is a workaround only for an API h34s does not offer yet. Configuration comes from a Scala API.
4. **Do not overbuild everyday pieces.** Where a good library works out of the box (kyo-sql, kyo-schema-json and -yaml, zio-schema-json, Skunk), guide users to it and show how to plug it into `AppHandler`. No wrapper modules.
5. **Schemas.** `NativeConverter` stays the default type class that h34s APIs ask for. h34s internals, the example and yurian.land use one schema derivation per model that gives JSON, `NativeConverter` and YAML. Write the `NativeConverter` bridges for Kyo Schema (new) and ZIO Schema (existing).
6. Design and review with the `functional-design` and `scala-functional-design` skills: pure values, a thin effectful shell, one wiring point (`AppHandler`), typed error domains, one resource per scope, separate models at boundaries.
7. Order of work: the h34s pull request first, then move yurian.land PR [#15](https://github.com/yuri4n/yurian.land/pull/15) onto the new API.
8. **HTTP model.** A handler is a function (a Kleisli arrow) from an immutable `Request` snapshot to the response body, or it sends a stream with `sendStream` and returns the `Sent` value it gives (item 27). Status, headers and cookies are local state of the computation (Kyo `Var[ResponseHead]`, ZIO `ZState[ResponseHead]`), with per-entry `OnFailure.Keep` and handler-level defaults. See [section 4](#4-the-http-model). <!-- claude 2026-10-20: changed: the rule sentence for sent streams (ADR-0004) -->
9. **Handler options and wire format.** `{ "data": ... }` for values, `{ "errors": [{ "message", "path"? }] }` for errors, 204 for `Unit`, unwrapped sent streams, all selectable through `HandlerOptions`. <!-- claude 2026-10-20: changed: sent streams (ADR-0004) -->
10. **Client disconnects.** Both runtimes interrupt the handler, wait for every request-scope and stream finalizer, write nothing, and only then settle the request. Guides must explain rollback risk and show a tested way to make a step finish.
11. The yurian.land port is verified against a locally published `0.2.0-SNAPSHOT`. Its CI may stay red until an h34s release. Publish nothing yet.
12. After the user approves PR #14, publish `0.2.0-alpha.1` to private GitHub Packages (Maven and npm) with matching tags and prereleases, then move yurian.land to it.

### 2026-10-18: the release and cloud sessions

13. The user approved PR #14 and asked to merge it into `main`, then tag `main` for `npm-v0.2.0-alpha.1` and `v0.2.0-alpha.1` and publish. Done: merge commit `433ac9d`, both tags on it, both release workflows green, both prereleases created. yurian.land then moved to `0.2.0-alpha.1`.
14. The user asked to move durable project knowledge into the repository, so that sessions without access to the maintainer's Mac (including Claude Code cloud sessions) can continue the work. This page, the tracking issue and the "Agent sessions" section of `AGENTS.md` are the result.

### 2026-10-19: the tracker, the identity and the next alpha

15. **nuxt4s is the umbrella project, and h34s is the library inside it**: the Scala.js modules over h3 and the npm tooling that links them into Nuxt. The library keeps its name. The Maven group moves to a nuxt4s namespace, and the repository will be renamed `nuxt4s` ([#21](https://github.com/yuri4n/h34s/issues/21)). <!-- claude 2026-10-19: created: the nuxt4s umbrella decision (#21) -->
16. **The public coordinates are renamed** ([#22](https://github.com/yuri4n/h34s/issues/22)): artifact ids `h34s-*` (the converter modules become `h34s-zio-converters` and `h34s-zio-json`), Scala packages `h34s.*`, and sbt project ids to match. There are no deprecated forwarders: the `dev.yuri4n.exts.*` packages and the `CommonEvHandler` alias are deleted, because nothing is deprecated before a non-alpha release. <!-- claude 2026-10-19: created: the coordinate rename decision (#22) -->
17. **The `exts` level goes** ([#23](https://github.com/yuri4n/h34s/issues/23)): the modules move to `scala-modules/<module>`, and the root keeps the name `scala-modules/`. <!-- claude 2026-10-19: created: the module layout decision (#23) -->
18. **`zio-migrations` moves under `examples/`** ([#24](https://github.com/yuri4n/h34s/issues/24)) and follows the examples' conventions: unpublished and compiled by CI. <!-- claude 2026-10-19: created: the zio-migrations decision (#24) -->
19. **The documentation moves** ([#25](https://github.com/yuri4n/h34s/issues/25)): `website/` becomes `docs/`, the guides move into `docs/content/`, and the maintainer documents (such as `docs/design/` and `docs/maintainers/`) stay outside the content collection. <!-- claude 2026-10-19: created: the docs move decision (#25) --> Done in the move pull request: a remark plugin in `docs/nuxt.config.ts` maps the guides' relative links to site routes, the site's status page and the consumer part of the release guide became the Releases page, and the release procedure moved to `docs/maintainers/releases.md`. <!-- claude 2026-10-20: changed: the move is done -->
20. **TypeScript generation uses the converter-driven generator of PR [#15](https://github.com/yuri4n/h34s/pull/15)**, not scala-ts, because scala-ts describes Scala types, not what a `NativeConverter` writes. PR #15 ships in `0.3.0-alpha.1` ([#27](https://github.com/yuri4n/h34s/issues/27)). <!-- claude 2026-10-19: created: the generator decision (#27) -->
21. **The Kyo API names are final** ([#28](https://github.com/yuri4n/h34s/issues/28)): `KHandler`, `Response`, `RuntimeConfig`, `Shared` and `KHubKv` stay. There is no "KIO" rename. <!-- claude 2026-10-19: created: the Kyo API naming decision (#28) -->
22. **CI gets a PostgreSQL 16 job** ([#29](https://github.com/yuri4n/h34s/issues/29)). PR [#45](https://github.com/yuri4n/h34s/pull/45) adds a `postgres` job that runs the Kyo example's `KyoSqlPostgresSuite` and the database part of `scripts/smoke.mjs`. <!-- claude 2026-10-19: created: the PostgreSQL CI job decision (#29) -->
23. **Finalizer failures: ZIO follows Kyo** ([#40](https://github.com/yuri4n/h34s/issues/40)). A finalizer that fails after the program produced a response is logged and does not change that response. A streamed body keeps its current behavior. Until the implementing pull request lands, [section 4.7](#47-errors-and-diagnostics) describes today's difference. <!-- claude 2026-10-19: created: the finalizer-failure decision (#40) -->
24. **`HandlerOptions.requireJsonContentType` is on by default from `0.3.0-alpha.1`** ([#41](https://github.com/yuri4n/h34s/issues/41)), with a test, a migration-guide entry and a release note. Until the implementing pull request lands, [section 4.3](#43-body-reading) and *(D-6)* describe today's opt-in default. <!-- claude 2026-10-19: created: the JSON content-type default decision (#41) -->
25. **The stale draft pull requests are closed** ([#26](https://github.com/yuri4n/h34s/issues/26)): #2, #3, #12 and #13, without merging. Their branches stay. <!-- claude 2026-10-19: created: the draft PR triage outcome (#26) -->
26. **The tracking model** ([#17](https://github.com/yuri4n/h34s/issues/17)). Every remaining item is a sub-issue of #17, labeled `area:*` and `kind:*` and shown on the maintainer's nuxt4s project board. `kind:decision` is the maintainer's inbox, and the milestone `0.3.0-alpha.1` is the provisional scope of the next prerelease. Decisions are recorded as comments on the sub-issues. <!-- claude 2026-10-19: created: the tracking model (#17) -->

### 2026-10-20: streams are sent, test helpers and two `$fetch` rules

<!-- claude 2026-10-20: created: the decisions of ADR-0004 and ADR-0006, implemented in the sendStream pull request -->

<!-- claude 2026-10-20: changed: items 29 and 30, the two ADR-0005 rules implemented in the sendStream pull request (review of PR #64) -->

27. **Streams are sent with `Response.sendStream`, not returned** ([ADR-0004](adr/0004-streams-are-sent.md), issue [#51](https://github.com/yuri4n/h34s/issues/51)). Both runtimes offer `Response.sendStream(stream)`, which gives `Sent`; a streaming handler returns that `Sent`, and a returned `kyo.Stream` or `ZStream` is a compile error whose message names `Response.sendStream(stream)`. The head is written at the call on every transport, and flushed over a socket (Q1, option A). A stream failure after the head fails the `sendStream` step with the stream's error; the core logs it once and writes nothing more (Q3, option B). The ZIO operation is `zioh3.Response.sendStream`, and `ZHandler.sendStream` is gone (Q8, option B). A caller's cancel of an in-process stream waits for the callee's whole request cleanup (Q16). See [section 4](#4-the-http-model) and [section 8](#8-streaming).
28. **One merged handler environment, and public test helpers** ([ADR-0006](adr/0006-handler-environment-and-test-helpers.md)). `HandlerEffects` carries one `Env[Fetch & HandlerTransport]`, because two entries break the documented `Env.run(service)(program(request))` wiring in Kyo RC7 (Q9, option A; see the appendix). `KHandler.test(handler)(request, fetch)` and `ZHandler.test(handler)(request)` provide the request scope, the head, a transport that collects a sent stream and (Kyo) a `Fetch`, and give a `TestResponse` with the head and the body (Q10, option B). `Fetch.fake` answers `$fetch` calls from a map and records them. `HandlerTransport.detached` is internal.
29. **`$fetch` tells `Streamed` from `Plain` by the marker or a stream content type** ([ADR-0005](adr/0005-fetch-in-handlers.md), Q2, option B). `FetchResponse.isStreamed` is true when the response carries `x-h34s-stream` or the content type `text/event-stream` or `application/x-ndjson`, and nothing else. The spike's third clause, "no `content-length`", is gone: it called every in-process response streamed. An in-process JSON response is now `Plain`, as it is over HTTP. The known gap: a JavaScript route that streams without either signal is `Plain`, so `raw` reads it to its end. Tests: "(a) JSON route in-process: no marker and no stream content type, so Plain without content-length; as[A] decodes it" and "(a) the same JSON route over real HTTP (absolute URL): Plain as well, and only this path sets content-length" (`NitroLocalFetchSuite`).
30. **Incoming credentials reach only in-process paths** ([ADR-0005](adr/0005-fetch-in-handlers.md), Q5, option B). A `$fetch` URL that starts with `/` still goes through Nitro's `event.fetch`, which forwards the incoming headers (Q6). Any other URL goes through `globalThis.fetch`, which Nitro's `localFetch` would call for it anyway, but without `fetchWithEvent`, so the request carries only the headers the caller passes and the body's own `content-type` and `content-length`. An empty-value override through `event.fetch` was not chosen: Node 24.22.0's fetch still sends `cookie` and `authorization` with an empty value. Because the global fetch needs no h3 event, an absolute URL also works where `event.fetch` is missing; an in-process path still needs Nitro. Test: "an absolute URL gets only the headers the caller passes: the incoming cookie, authorization and entity headers stay behind" (`NitroLocalFetchSuite`), which replaces the spike's test that measured the leak; test (e) still proves the in-process forwarding. The other ADR-0005 items follow in the next pull request: the `app.baseURL` prefix and the minimal context (Q6), typed body errors and `headerValues` (Q14), the raw untyped verbs (Q15, documentation), and the ZIO `$fetch`.

### 2026-10-20: named model types

<!-- claude 2026-10-20: created: the named-model decisions of issue #66 (ADR-0014) -->

31. **Models have names in TypeScript** ([ADR-0014](adr/0014-named-model-types.md), issue [#66](https://github.com/yuri4n/h34s/issues/66)). Item 20 stays: the generator describes what each `NativeConverter` writes. The name rides that description as `WireType.Named(name, target, scalaType)`. The Kyo bridge names every record and sum after the simple name of its `Structure.Type` (the full name, from the type's tag, goes into error messages and the models file's comments), and the ZIO bridge names every record after its nominal `TypeId`. A generic instantiation is written out (Kyo: non-empty `typeParams`; ZIO's `TypeId` has no type arguments, so `h34s-scala` treats one Scala type with two structures as a generic). `h34s-scala` writes one `h34s-models.d.ts` per output directory, each route declaration imports the names it uses, and the manifest lists the models file. One name with two structures fails the generation and names both Scala types and the routes. The Nuxt module maps `#h34s/models` to the models of one output, or to a build-directory declaration that re-exports every output. Only response models are named in this round; request-body models come with the typed route registry ([#46](https://github.com/yuri4n/h34s/issues/46)). `render` keeps writing every model out, so the record's `success` string is unchanged and an older `h34s-scala` still writes correct declarations; the record adds `named`.

### Still open

The user has not decided yet:

- The Maven group id, and whether h34s gets its own directory in the renamed repository or its own repository ([#21](https://github.com/yuri4n/h34s/issues/21)). <!-- claude 2026-10-19: changed: open item from #21 -->
- Whether the kyo-sql code is also checked inside a Nitro bundle (it ran in Node through sbt), and whether the PostgreSQL job also gates release tags, because `release.yml` reuses `scala.yml` ([#29](https://github.com/yuri4n/h34s/issues/29)). <!-- claude 2026-10-19: changed: open items from #29 -->
- Whether the example's `build` and `generate` scripts can drop `--max-old-space-size=8192`. With `scalaPrerender: 'stub'`, the example's build passed with a 4096 MiB heap limit on a cloud VM; nobody has measured it on the maintainer's Mac yet. The heap reduction itself is decided: see "Build heap" in [section 9.12](#912-examples) ([#30](https://github.com/yuri4n/h34s/issues/30)). <!-- claude 2026-10-19: changed: open item from #30, narrowed to the heap option because the delegated remedy is decided in section 9.12 -->
- How a no-content body with a custom success status is typed for `$fetch` ([#46](https://github.com/yuri4n/h34s/issues/46)). <!-- claude 2026-10-19: changed: open item from #46 -->

Each one is a sub-issue of the tracking issue [#17](https://github.com/yuri4n/h34s/issues/17), with its context and its "done when". <!-- claude 2026-10-19: changed: pointer to the sub-issues of #17 -->

## 2. Module graph and dependency direction

`0.2.0-alpha.1` publishes nine modules. Every module builds with Scala 3.9.0, Scala.js 1.22.0 and JDK 25 (Kyo 1.0.0-RC7's macros need Java 25 at build time). ZIO is 2.1.26 because kyo-zio 1.0.0-RC7 pins it; zio-json is 0.7.44, zio-schema 1.7.5, native-converter 0.9.0. Java and sbt are build tools only: applications run in Nitro on Node.

```text
h3-ext                                 native layer, no effect library
├── kyo-h3-ext                         Kyo core
│   ├── kyo-nuxthub-ext                Kyo storage
│   └── zio-h3-ext                     ZIO adapter (+ kyo-zio, ZIO, native-converter-zio-ext)
│       └── zio-nuxthub-ext            ZIO storage (+ kyo-nuxthub-ext)
├── native-converter-kyo-schema-ext    Kyo Schema bridge (+ kyo-schema)
├── native-converter-zio-schema-ext    ZIO Schema bridge (+ zio-schema)
└── native-converter-zio-ext           ZIO converters (+ ZIO)
    └── native-converter-zio-json-ext
```

- Dependencies point one way: native layer, then Kyo core, then the Kyo storage bridge and the ZIO adapter, then ZIO storage. Schema bridges depend only on the native layer and their schema library.
- Removed in `0.2.0-alpha.1`: `zio-js-ext` (empty), `nuxthub-ext` (folded into the native layer's internals) and `zio-h3-schema-ext` (replaced by `derives NativeSchema` and `request.body.json`). ScalablyTyped is gone; the h3 facades are hand-written and internal.
- Not published: the examples, including `zio-migrations-ext` in `examples/zio-migrations` (an experiment that compiles on 3.9.0, outside the library aggregate since #24), and the aggregates. <!-- claude 2026-10-20: changed: zio-migrations is an example (#24) -->
- Two checks enforce the direction. `scripts/check-release.mjs` reads the generated POMs: a Kyo artifact must not depend on ZIO, `h3-ext` must not depend on any effect runtime, a ZIO converter must not depend on Kyo, and only schema bridges may depend on a schema library. `scripts/check-consumers.mjs` builds four fresh consumers (Kyo, ZIO, Kyo Schema, ZIO Schema), serves real h3 requests through a preemptive router as Nitro does, and fails when a Kyo consumer resolves any `dev.zio` artifact.

<!-- claude 2026-10-20: changed: links to the guides' new paths under docs/content/ (issue #25) -->

[Architecture](../content/2.reference/1.architecture.md) has the full table of what each module offers.

## 3. Layers

```text
Business logic   your services and programs (Kyo `A < S` or ZIO), owned by the application
Interfaces       your traits + h34s values (Request, ResponseHead, SafeError, Secret, ...)
Implementation   the h34s core runtime (Kyo), internal native facades, your interpreters
Application      your AppHandler: config -> Shared resources -> Env / ZLayer wiring
```

- **Native gatekeeping.** Every `js.*` value and native exception is converted inside `h3-ext` and `kyo-h3-ext`. Public signatures contain no `js.*` type, no facade and no `org.scalajs.dom` type.
- **Handlers are an error-domain boundary.** A `SafeError` becomes its approved status and body; everything else becomes an opaque 500.
- **`AppHandler` belongs to the application.** It is the one wiring point, a function from handler to handler that supplies services, resources and policy around `KHandler.define` or `ZHandler.define`. h34s does not export or generate it.

## 4. The HTTP model

### 4.1 The request snapshot

`Request` is an immutable snapshot built once per request: `method`, `path`, `header` / `headerValues` / `headers` (lower-case names, duplicates joined with `", "` except in `headerValues`), `query` / `queryAll`, `routerParam` / `routerParams`, `cookie` / `cookies`, `bearerToken` (exactly one `Authorization: Bearer <token>` header, otherwise `None`), `development`, `debug` and `body`.

- `development` and `debug` come only from the h34s Nitro plugin. A request can never set them.
- The body is not part of the snapshot. It is a one-shot input that you read through runtime extensions (`request.body.json[A]`, `request.body.text()`). A second read returns the same bytes.
- `Request.fake(...)` builds a request without h3, so a test can call a handler function directly with `KHandler.test` or `ZHandler.test`. <!-- claude 2026-10-20: changed: the test helpers (ADR-0006) -->
- `Request.toString` is redacted to `Request(<method> <path>)`, because headers can carry credentials.

### 4.2 Handler shape

<!-- claude 2026-10-20: changed: the handler environment, Sent bodies and the test helpers (ADR-0004, ADR-0006) -->

```scala
// Kyo core
type HandlerEffects = Async & Scope & Abort[Throwable] & Var[ResponseHead] & Env[Fetch & HandlerTransport]
KHandler.define(request => program)                 // program: B < HandlerEffects
KHandler.define(options: HandlerOptions)(request => program)
Response.sendStream(stream)                         // Sent < HandlerEffects
KHandler.test(handler)(request, fetch)              // TestResponse[B] < (Async & Abort[Throwable])

// ZIO adapter
type HandlerEnv = ZState[ResponseHead] & Scope & HandlerTransport
ZHandler.define(request => program)                 // program: ZIO[HandlerEnv, ErrorResponse | Throwable, B]
ZHandler.define(options)(request => program)
Response.sendStream(stream)                         // ZIO[ZState[ResponseHead] & HandlerTransport, Throwable, Sent]
ZHandler.test(handler)(request)                     // IO[E, TestResponse[B]]
```

The body type needs a `ResponseBody[B]` (Kyo) or `ZResponseBody[B]` (ZIO) instance: any type with a `NativeConverter`, `Unit`, or `Sent`. There is no `Env[Request]`, and `sendStream` is the only method that writes the native response while the program runs. `HandlerTransport` is the request's native response; only h34s builds one (`define` per request, the test helpers in tests).

### 4.3 Body reading

<!-- claude 2026-10-19: changed: the rows for a JSON read without a content type, with and without a body (issue #41) -->
| Input | Result |
| --- | --- |
| Body larger than the limit (checked from `Content-Length`, then counted while reading) | `HttpError` 413. h34s never buffers more than the limit |
| `json[A]` with a content type that is not JSON | `HttpError` 415 |
| `json[A]` with a body but without a content type | `HttpError` 415, unless `HandlerOptions.requireJsonContentType` is false (then accepted). The default is `true` since `0.3.0-alpha.1`. The check runs after the read, so such a body is buffered up to the limit first |
| `json[A]` without a body (no bytes) and without a content type | Decodes like JSON `null`: a model is 422, `Option[A]` is `None` |
| Invalid JSON | `HttpError` 400 |
| A shape the converter rejects, or a schema check that fails | `InvalidInput` 422 |
| Any other exception from a decoder | A defect: opaque 500 |

The default limit is 1 MiB (`BodyLimit.default`); `HandlerOptions.bodyLimit` changes it. h3's own `readBody` is not used for typed reads, because it parses JSON and URL-encoded bodies whatever the content type, with no size limit.

### 4.4 Response head

- Status, headers and cookies live in `Var[ResponseHead]` (Kyo) or `ZState[ResponseHead]` (ZIO). `Response.status`, `header`, `appendHeader`, `cookie`, `deleteCookie`, `redirect` and `head` change or read it.
- The state starts at `ResponseHead.empty`. The core composes the handler defaults with what the program set, using the pure `ResponseHead.forSuccess` and `forFailure`.
- For a value or `Unit` body, h34s writes the head and the body once, after the program and its finalizers finish. A streaming handler ends with `sendStream`, which writes the head as it stands at the call and then the elements; over a Node socket it also flushes the head at once (`flushHeaders`). The request scope stays open until the stream ends or is cancelled. No other public API writes the native response while the program runs. <!-- claude 2026-10-20: changed: the head of a sent stream is written at the call (ADR-0004, Q1) -->

### 4.5 The failure rule

On failure, the status always comes from the error, so a 2xx never leaks. The response keeps only the handler defaults, the entries that the program marked `OnFailure.Keep`, and the error's own headers (for example `WWW-Authenticate` on a 401). Everything else that the program set is discarded. On success, the whole head applies.

Inside the program, recovering from a failure does not roll the head back. This is true for Kyo `Abort.recover` and ZIO `catchAll` alike.

### 4.6 HandlerOptions and the wire format

<!-- claude 2026-10-19: changed: `requireJsonContentType` defaults to `true` (issue #41) -->
<!-- claude 2026-10-20: changed: the stream row: sent streams (ADR-0004) -->
`HandlerOptions(success, errors, defaults, bodyLimit, requireJsonContentType)`; `HandlerOptions.default` is `Data`, `List`, no defaults, 1 MiB, `true`.

| Outcome | Status | Body with the default options |
| --- | --- | --- |
| A value | 200, or the status the program set | `{"data": <value>}`. With `SuccessEnvelope.Raw`: the value itself, see below |
| `Unit` | 204 | None |
| A sent stream (`Sent`) | 200, or the status the program set before `sendStream` | The streamed strings, never wrapped |
| A `SafeError` | Its status | `{"errors": [{"message": "..."}]}`; `InvalidInput` gives one entry per app-authored problem, with `path` when known, never input values |
| Anything else | 500 | `{"errors": [{"message": "Internal server error"}]}` |

`ErrorEnvelope.SingleOrList` sends `{"error": {...}}` for one entry and `{"errors": [...]}` for several. With `SuccessEnvelope.Raw`, a string goes out as `text/plain; charset=utf-8` and any other value as `JSON.stringify` text with `application/json`, both with `X-Content-Type-Options: nosniff`, unless the head already sets a content type.

### 4.7 Errors and diagnostics

<!-- claude 2026-10-19: changed: the "Finalizer failures are logged and keep the response, in both runtimes" item: ZIO follows Kyo on finalizer failures (issue #40); the request-scope boundary (review) -->

- **`SafeError` is the only error data a client may see.** `HttpError`, `InvalidInput` and ZIO's `ErrorResponse` (rendered through an internal `SafeError`) are the public kinds. A `SafeError` must have a status from 400 to 599 and non-null entries; otherwise it is treated as a defect.
- **A thrown `SafeError` is a defect.** Only a typed failure is approved. A `SafeError` thrown by a decoder, converter or stored-value read is never shown.
- **Every other `Throwable` is an opaque 500.** h34s logs it on the server.
- **Diagnostics.** In Nuxt development mode, or when debug mode is on, the internal-error entry also carries `"debug": {"cause": "..."}`: the exception chain with stack text in Kyo, `Cause.prettyPrint` in ZIO. Turn debug mode on with `NUXT_H34S_DEBUG=true` or `runtimeConfig.h34s.debug: true`. It is never derived from Nuxt's own `debug` option, and a request can never turn it on. Keep it off in production.
- **`ConfigError`** (missing or invalid runtime config, missing Nitro plugin) is never public: it is an opaque 500.
- **Finalizer failures are logged and keep the response, in both runtimes.** Kyo `Scope` finalizer errors are logged and do not change the response (Kyo semantics). The ZIO adapter does the same since the next release (provisionally `0.3.0-alpha.1`): it writes `h34s: a ZIO finalizer failed` with `Cause.prettyPrint` through `console.error` (Kyo's own message is `Scope finalizer failed`, through its `Log`), and keeps the program's own `Exit`, for value, `Unit` and streamed bodies, and on a client disconnect. The log helper is total: a cause that cannot be rendered is logged as a placeholder and never fails the request. A success keeps its status, head and body; a safe failure keeps its status and entries. The rule covers the request scope that the adapter owns. A release that dies inside the handler's own `ZIO.scoped` block, or a scoped layer provided inside the handler, fails the program under ZIO's `acquireRelease` semantics; at the boundary the program died, and the adapter cannot tell that cause from any other defect without guessing, so it answers 500 as before. Kyo's nested `Scope.run` logs and keeps the value instead: an accepted difference, because inner-scope semantics belong to each runtime (orchestrator decision, 2026-10-19; the maintainer may open an issue to cover inner scopes). The maintainer decided this on 2026-10-19 ("align them", issue #40). Before, a dying ZIO finalizer turned a value or `Unit` response into a 500, because the defect surfaced in the ZIO `Exit`.

### 4.8 Client disconnects and uninterruptible steps

When the client disconnects, h34s:

1. interrupts the handler (Kyo fiber or ZIO fiber),
2. waits for every request-scope and stream finalizer, asynchronous ones included, and for the cleanup of work forked with Kyo's structured combinators (`Async.timeout`, `race`, `gather`, `zip`, a concurrent `foreach`) or as ZIO child fibers,
3. writes nothing, marks the h3 event as handled, removes its listener, and only then settles the request.

It never starts a handler on a response that is already closed, and a late `close` event is harmless. Interrupted work can roll back, for example an open database transaction. A step that must finish goes in `KHandler.uninterruptible(step)` (Kyo) or `ZIO.uninterruptible` / `.uninterruptible` (ZIO). Kyo's own `Async.uninterruptible` is not enough: it runs the step on an unowned fiber, so the request would settle and release its scope while the step still runs.

The disconnect tests in `HttpHandlerSuite` (Kyo) and `HandlerBoundarySpec` (ZIO) prove this order. Do not change it to "settle first, clean up in the background".

## 5. Configuration and application resources

### 5.1 Runtime config and secrets

- `RuntimeConfig.get[A](request)` decodes Nuxt `runtimeConfig` (private and public) as Nitro resolves it for this request. `RuntimeConfig.load[A]` decodes Nitro's shared startup copy, for application-scoped resources. ZIO: `ZRuntimeConfig.get` / `load`.
- `NUXT_*` environment variables override declared keys at runtime. Nitro parses them with `destr`, so `"123"` becomes a number and `"true"` a boolean; a model that expects a string then fails with `ConfigError`.
- A decode failure is a `ConfigError` (opaque 500) that names only the path. A missing plugin record is `ConfigError("h34s Nitro plugin is not installed")`.
- `Secret` prints as `Secret(<redacted>)`, compares in constant time for its length with `matches`, and gives the plain value only through `reveal`. Both schema bridges export a `secretSchema` so a config model with a secret can still use one schema derivation.

### 5.2 Application-scoped resources

- `Shared(acquire)` (Kyo) and `ZShared.layer(layer)` (ZIO) acquire a resource on first use, share it across requests, and release it when Nitro closes or on `close`.
- Concurrent first calls share one acquisition. A failed acquisition is not cached. An interrupted waiter does not cancel the shared acquisition. `close` releases once, and a later `get` acquires again.
- Reusing a `ZLayer` value does not memoize it across requests, and a `ZEnvironment` does not own cleanup. That is why `ZShared` exists.

### 5.3 The Nitro plugin records

The `@yuri4n/h34s-nuxt` module registers `runtime/h34s.mjs` (`runtime/development.mjs` re-exports it for one release). `runtime/context.mjs` builds the records with `createH34sPlugin({ useRuntimeConfig, useStorage, development })` and imports nothing from Nitro, so node tests inject fakes.

| Record | Where | Fields |
| --- | --- | --- |
| Startup | `globalThis[Symbol.for('h34s.nitro')]` | `version: 1`, `development`, `debug`, `runtimeConfig()` (Nitro's frozen startup copy), `storage(base)`, `onClose(fn)` |
| Request | `event.context.h34s`, set in Nitro's `request` hook | `version: 1`, `development`, `debug`, `runtimeConfig()` (lazy; Nitro clones and caches it per event), `storage(base)` |

- Both records are frozen. `development` is `import.meta.dev === true`. `debug` is `useRuntimeConfig().h34s?.debug === true`, read once at startup.
- The module declares `runtimeConfig.h34s.debug: false` so `NUXT_H34S_DEBUG=true` can override it. A value the application already set wins.
- The Nitro `close` hook releases every registered closer with `Promise.allSettled` (one failure does not stop the others), clears the set and removes the startup record.
- Scala reads these records only through internal facades. Application code never sees them.

## 6. Schema strategy

| | Kyo (primary) | ZIO |
| --- | --- | --- |
| Derive | `derives Schema, NativeSchema` | `derives Schema, NativeSchema` (needs `import zio.schema.derived`) |
| HTTP and native values | `native-converter-kyo-schema-ext` | `native-converter-zio-schema-ext` |
| JSON | kyo-schema-json `Json.encode` / `decode` | zio-schema-json `JsonCodec` |
| YAML | kyo-schema-yaml | None on Scala.js: zio-json-yaml has no Scala.js artifact |
| Validation | `Schema[X].check(...)`, run by the bridge on decode | `@validate` / `Schema.validation`, run by the bridge on decode |

- `NativeConverter` stays the default type class. A `derives NativeConverter` model works with every h34s API without a schema library; shape errors are a generic 422, and domain rules are your code's job.
- No blanket instance. Only types that `derives NativeSchema` (or call `NativeSchema[A]`) get a schema-derived converter, so it never competes with a converter the application already has.
- The bridges choose no JSON or YAML library. Applications add `kyo-schema-json`, `kyo-schema-yaml` or `zio-schema-json` themselves.
- Encoding goes straight to native JavaScript values. There is no JSON text round trip, and tests replace `JSON.stringify` and `JSON.parse` with throwing functions to prove it.
- Wire shapes match the JSON library: the Kyo bridge output equals `JSON.parse(Json.encode(x))` for records, options, enums, sealed traits and the other tested shapes.

<!-- claude 2026-10-20: changed: links to the guides' new paths under docs/content/ (issue #25) -->

[Schema](../content/1.guide/07.schema.md) documents the user-facing rules.

## 7. The ZIO bridge

The ZIO adapter runs ordinary ZIO programs on a ZIO `Runtime` and bridges only at the boundary. It never translates ZIO programs into Kyo programs.

### Why not kyo-zio's `ZIOs.get` and `ZStreams.get`

- `ZIOs.get` forks the ZIO effect and, on Kyo interruption, calls `fiber.unsafe.interrupt`, which returns at once. The Kyo side resumes before the ZIO finalizers finish, so a ZIO resource could outlive the request that opened it. It also always uses `zio.Runtime.default` and rejects ZIO environments at compile time.
- `ZStreams.get` closes the ZIO stream scope from an inner `Scope.run` and pulls with `ZIOs.get`, so a cancelled stream would not wait for its ZIO finalizers.

Both break the disconnect rule, so they are not used on request paths. The opposite direction, kyo-zio's `ZIOs.run` (Kyo inside ZIO), does interrupt and await the Kyo fiber, so `ZShared.get` / `close` and every `ZHubKv` operation use it.

### How the adapter bridges

- **`ZioCall`.** It forks the ZIO effect on the given `Runtime` and observes its `Exit`. The observer completes two Kyo promises: an interruptible one for the normal wait, and an uninterruptible one that a `Scope.ensure` finalizer in the request scope waits on. On Kyo interruption the finalizer interrupts the ZIO fiber and waits for its `Exit`, so ZIO finalizers finish before the core settles the request.
- **One ZIO fiber per request** (`internal/ZioRequest.scala`). It runs inside `uninterruptibleMask` and owns the `ZState` head: `ZIO.stateful(ResponseHead.empty)(program.exit.zip(ZIO.getState[ResponseHead]))`. The Kyo side then sets its `Var` to that head and re-raises the `Exit`. The core runs `Var` outside `Abort`, so the failure renderer still sees the head.
- **The request `Scope`.** The Kyo side creates the ZIO `Scope` and always closes it after `stop`, whatever the fiber's `Exit` was (closing twice is a no-op) *(D-13)*. <!-- claude 2026-10-19: changed: a dying finalizer is logged (issue #40) --> For every body the fiber closes it first, after the program; a dying finalizer is logged and the program's `Exit` stays as it was (see 4.7). A stream sent with `Response.sendStream` has ended or was cancelled by then, because the program waits inside `sendStream` *(dev. 26, changed by ADR-0004)*. <!-- claude 2026-10-20: changed: streams are sent inside the program, so the fiber closes the scope for every body (ADR-0004) -->
- **Exit mapping.** See *(dev. 27)* below. In short: a single typed `SafeError` or `ErrorResponse` stays approved; everything else becomes an internal `ZioDefect` carrying `Cause.prettyPrint` for diagnostics, and renders as the opaque 500.
- **Runtime.** `ZHandler.define` and `ZShared.layer` take `(using runtime: Runtime[Any] = Runtime.default)`.

## 8. Streaming

<!-- claude 2026-10-20: changed: streams are sent with Response.sendStream (ADR-0004): the head at the call, a failure after it fails the step, in-process streams; the producer after the head; which Kyo scope sees the outcome -->

- **Sent, not returned.** A handler sends a `kyo.Stream[String, S]` or a `ZStream[Any, E, String]` with `Response.sendStream(stream)` and returns the `Sent` value it gives *(item 27)*. `Sent` carries its request's transport, so a `Sent` of another request is a defect. A second `sendStream` in one request is a defect too; the first stream stays the response. Node writes only strings and bytes, so element types other than `String` are not offered *(D-3)*. Encode structured elements yourself, for example as NDJSON.
- **The transport.** `HandlerTransport` holds the h3 event, the options and three hooks: settle the h3 promise early (socketless), end the connection after a failure (`abandon`: it stops listening for a disconnect, then destroys the socket, so ending it does not interrupt the program), and a promise that completes after the whole request finished. Its state is `Open` (the core writes), `Streaming` (the head is not out yet) or `Sent` (the head is out; the core writes nothing more).
- **Kyo: one producer fiber, demand-gated.** The producer runs the whole stream (and its resources) in its own scope. Two capacity-1 channels carry requests and deliveries, so no chunk is computed before the Web `ReadableStream` pulls it. The producer runs under the same never-interrupted supervisor as requests (`internal/Supervised.scala`), so a cancel awaits the stream's finalizers *(dev. 6)*.
- **ZIO: a hand-written pull.** One forked ZIO fiber per chunk and one producer-scope finalizer that stops an in-flight pull and closes the ZIO stream scope, both awaited. Empty chunks are skipped. `zioh3.Response.sendStream` runs the core's send through `internal/KyoCall` (a supervised Kyo worker whose interruption waits for its scope), and it keeps the stream's own `Cause` for the program's failure: the core carries it as a `Throwable`, and a `tapErrorCause` records the original (an interruption alone is a cancel, not a failure).
- **The head at the call** *(Q1)*. `sendStream` composes the defaults with the head the program set, adds `x-h34s-stream: 1` and, without a content type, `text/plain; charset=utf-8` and `X-Content-Type-Options: nosniff`, and writes it when it is called. Over a Node socket it then calls `flushHeaders()`, so the head leaves before the first element. A head that cannot be written is cleared, and the call fails before anything reached the client, so the core answers with the failure rule; when part of it had already left (Node's `headersSent`), the transport abandons the connection instead. The producer fiber is created only after the head is out, by `Scope.acquireRelease`, which registers its release in the step that creates it: a head that cannot be written leaves no producer, and an interrupt cannot separate the fiber from its release. A status, header or cookie that the program sets after the call stays in `Var`/`ZState` and is never written.
- **A failure after the head** *(Q3)*. The status is fixed. Over a socket, the transport abandons the connection (no final chunk, so the response cannot look complete); without one, the caller's reads fail. The `sendStream` step fails with the stream's error (the producer's own failure or panic; the write error when the writer side failed), so the program, and a `Scope.run` or transaction around the step inside it, see it. In Kyo, a finalizer that the program adds directly to the request scope gets `Absent` even then: the supervisor closes the request scope after the worker, with the worker's result as a value (`internal/Supervised.scala`), and `work` has already turned a failure into `Completion.Failed`. ZIO's request `Scope` is closed with the program's `Exit` (`ZioRequest.closeAndLog`) and sees the failure. See 9.7. The core logs one failure after the program: the program's own, or the stream's when the program recovered from it.
- **Without a socket** (Nitro's in-process local fetch, node-mock-http). h3's `sendStream` keeps the stream in `res._data` and resolves at once. h34s writes the head first, encodes chunks as UTF-8 bytes (a Web `Response` accepts only `Uint8Array`), and settles the h3 promise at once, because the local fetch copies status and headers when it settles. The program and the request scope then wait until the caller reads the stream to its end or cancels it. A cancel interrupts the producer and resolves only after the producer and the callee's whole request finished *(Q16)*. Nothing signals a disconnect in-process, so an unread body that nobody cancels keeps the callee open ([ADR-0008](adr/0008-streaming-support-limits.md)).
- **A disconnect** interrupts the handler and the producer, waits for the stream's finalizers and every scope around `sendStream`, then releases the request scope. A client disconnect after the head is not logged as a failure.
- **Tests.** The core test helpers collect a sent stream (`TestResponse.Body.Streamed`) in the stream's own scope, so its resources close when it ends, as in a real request. They report the head as it stood at the `sendStream` call, the one a real transport writes, not the head at the end of the program.

<!-- claude 2026-10-20: changed: links to the guides' new paths under docs/content/ (issue #25) -->

[Streaming](../content/1.guide/09.streaming.md) has the user guide.

## 9. Implementation deviations

Each item says what differs from the first plan and why. Superseded items are folded into the item that replaced them.

### 9.1 Native layer and build

- **Facades only for what the core calls** *(dev. 1)*. `h3-ext` has internal facades for the h3 functions the core uses (`defineEventHandler`, `sendStream`, `setResponseStatus`, `setResponseHeader`, `appendResponseHeader`, `setCookie`, `deleteCookie`, `getQuery`, `getRouterParams`, `parseCookies`), not all ~25 functions the research listed. Unused facades are untested surface; add each one with its first caller.
- **`BodyLimit` lives in `h3-ext`** *(dev. 2)* because the ZIO adapter uses it too. `kyoh3` re-exports it.
- **Build wiring** *(dev. 14)*. `native-converter-zio-ext` depends on `h3-ext` and re-exports `unitNativeConverter`. `kyo-h3-ext` tests use `h3-ext` test fixtures; the storage and ZIO tests reuse the Kyo test fixtures (`NitroGlobal`). `zio-migrations-ext` is an unpublished example in `examples/zio-migrations` (#24) and compiles on 3.9.0. The unused `sbt-scala-ts` plugin entry is removed from `project/plugins.sbt`; TypeScript declarations come from the generator of decision 20. <!-- claude 2026-10-20: changed: zio-migrations is an example (#24); sbt-scala-ts removed -->
- **Tests use a real h3.** A pinned `h3@1.15.4` lives in `scala-modules/h3/test-js` (with a lockfile) and is put on `NODE_PATH` for Scala.js tests. <!-- claude 2026-10-20: changed: the flattened path (#23) -->
- **`zio-h3-schema-ext` removed** *(dev. 31)*. Its contracts (S1-S7) moved to zio-h3's `RequestBodySpec` with the new wire format: malformed JSON is 400 (was 422), a wrong shape a generic 422, failed checks 422 entries with paths. Also removed: the `fromNative_` extension (it put `js.Any` in a public signature and made decoder throws typed). The `TaskStream`, `RStream` and `URStream` aliases stay.
- **Release tooling** *(D-18, B1, SEC-7)*. `check-release.mjs` requires `.github/release-notes/<version>.md` when it runs on a tag, so a missing notes file fails before publication. `check-consumers.mjs` installs the pinned h3 fixture with `npm ci` and runs npm and Node without GitHub credentials; only sbt gets the token.
- **npm version** *(D-16, AD-4)*. `packages/nuxt/package.json` follows `version.sbt`, because the Nitro plugin contract changed with the Scala modules. Scala `0.2.0-alpha.1` needs `@yuri4n/h34s-nuxt@0.2.0-alpha.1`; with the `0.1.0-alpha.1` plugin, `RuntimeConfig` and `KHubKv.mount` fail with the "plugin is not installed" `ConfigError`.

### 9.2 Request and body reading

<!-- claude 2026-10-19: changed: the "Strict JSON content type is the default" item: the strict JSON content type became the default (issue #41) -->

- **Snapshot details** *(dev. 10)*. `Request.toString` is redacted. The first body read fixes the outcome: a body above a limit stays rejected, and a later read with a smaller limit also gets 413. An absent body is `""` for `text()`. `JSON.parse` drops `__proto__` keys.
- **Body limit plumbing** *(dev. 18)*. `RequestBody.defaultLimit` (`private[exts]`) carries `HandlerOptions.bodyLimit`; `json[A]` uses it and `text(limit = body.defaultLimit)`. `Request.fake` gained `debug` and `bodyLimit` parameters, appended so positional calls keep working.
- **An absent JSON body decodes like JSON `null`** *(D-5, R2)*. Passing `undefined` to a derived converter made native-converter's error path fail with a fatal `UndefinedBehaviorError` in fastLink builds (Kyo answered 500, ZIO hung). Now products fail with a normal 422 and `Option[A]` decodes to `None`. A `VirtualMachineError` from a converter becomes an `IllegalStateException` defect, so it can never hang a ZIO request.
- **Strict JSON content type is the default** *(D-6, SEC-8, issue #41)*. In `0.2.0-alpha.1`, `HandlerOptions.requireJsonContentType` defaulted to `false` and kept the agreed "JSON or absent" rule. Since `0.3.0-alpha.1` it defaults to `true`, a decision the maintainer delegated ("do what you think best", 2026-10-19): browsers send some cross-site requests without a content type and without a CORS preflight, and a safe default protects cookie-authenticated routes that change state without any setup. `HandlerOptions(requireJsonContentType = false)` restores the old rule for one handler. `Request.fake` follows the same default and has the same parameter, so a unit test sees the rule that production applies. A request without a body needs no content type (orchestrator decision, 2026-10-19): `BodyDecoding.acceptsJsonBody` checks a missing content type after the read, so `json[Option[A]]` of a bodiless request stays `None`. A present non-JSON content type still fails before the read.
- **`Request.fake(runtimeConfig = Some(json))`** *(D-15, AD-8)* lets tests decode runtime config without `js.*`.
- **A request stream that already ended** *(R8)*. The body reader fails at once when `readableEnded` or `destroyed` is true, instead of waiting forever. It does not use `req.complete`, which is also true on a fresh request whose small body already arrived.

### 9.3 Response head

- **The `Var` starts empty; the core composes defaults** *(dev. 3)*. `Response.head` therefore shows only what the program set. The ZIO adapter does the same with `ZIO.stateful(ResponseHead.empty)`.
- **Extra pure values** *(dev. 9)*: `DeleteCookie`, `HeaderMode`, `ResponseHead.++`, `forSuccess`, `forFailure`, more `Status` constants, and exactly the planned `HttpError` helpers.
- **Validation timing.** Kyo `Response.*` checks header names and values, cookie names and redirect statuses in the continuation (`Var.use`), not when the value is built, so a hoisted value is checked too *(D-8, AD-5; replaces dev. 8)*. ZIO's `Response.*` die inside the effect *(dev. 29)*. Both give the same opaque 500.
- **Cookie deletion** *(D-7, SEC-6, AD-6)*. `DeleteCookie` has `secure` (default `true`), and `withoutCookie` / `Response.deleteCookie` take `domain`, `secure` and `onFailure` in both runtimes. h3's `deleteCookie` sends no `Secure` attribute, and browsers ignore a `Set-Cookie` for a `__Secure-` or `__Host-` name without it.

### 9.4 Rendering and the wire

- **`Unit` answers 204 by resolving `null`** *(dev. 15)*. h3 1.15.4 and Nitro 2.13.1's h3 1.15.5 send a `null` result with `sendNoContent` (204, or the status already set if it is not 200; no body). `undefined` falls through to the next handler in a plain `createApp` and becomes `null` only in a preemptive router, which Nitro uses. The core sets 204 when the head has no status and keeps a status the handler set. Side effect of h3: an explicit 200 with a `Unit` body is sent as 204.
- **Raw values** *(dev. 16, D-4, SEC-3)*. A value that converts to `undefined` or `null` is a 204, so its generated type says `undefined` (what `$fetch` returns for a 204), not `null`. A raw value never reaches h3's type sniffing: h3 sends a string as `text/html` and treats any object with a `buffer` key as bytes, which can hang the response (`?buffer=1` on a handler that echoes the query). <!-- claude 2026-10-19: changed: the generated type of a Raw value that converts to null -->
- **Non-finite numbers are a defect.** `JSON.stringify` writes `NaN` and the infinities as `null`, which a generated `number` does not describe. `Rendering.successBody` walks a value body (both envelopes) and throws for them, so the response is the opaque 500 under the failure rule (defaults and `OnFailure.Keep` entries stay). <!-- claude 2026-10-19: changed: added the rule for non-finite numbers -->
- **`InvalidInput.message`** *(dev. 19)* joins entries as `path: message` with `"; "` for logs. It is never sent; the wire carries the entries.
- **`ErrorEntry`'s converter decodes too** *(dev. 20)*. `path` that is `null` or absent is `None`. An `undefined` value is reported at the root, because native-converter's `ParseState.fail` would call `JSON.stringify(undefined)`, a fatal error under checked semantics.

### 9.5 Errors and their approval

<!-- claude 2026-10-19: created: the "A finalizer failure is not part of the approval" item: finalizer failures do not take part in the approval (issue #40); the inner-scope tests (review) -->

- **The core runs `Abort.run[Any]`** *(dev. 4)*. Kyo RC7's `Abort.run[Throwable]` turns every thrown exception into a typed failure, which would approve a thrown `SafeError`. `Abort.run[Any]` keeps thrown exceptions as panics, and an internal `Interop.typed` narrows the result. Streams keep the producer's own failure or panic classification.
- **Out-of-range statuses** *(dev. 7)*. A `SafeError` with a status outside 400-599 is a defect, so a failure can never answer 2xx or 3xx.
- **Entry approval** *(dev. 17)*. Empty `errors`, a null entry or message, `Some(null)` as a path, or an `errors` method that throws makes the error a defect.
- **`failWith`** *(D-9, AD-9)*. `KHandler.failWith(status, message)` is typed `Nothing < Abort[HttpError]`. ZIO's `ZHandler.failWith` keeps `IO[ErrorResponse, Nothing]` to keep `0.1.0` call sites; `ErrorResponse` cannot extend `SafeError` because its `errors` field clashes.
- **`ErrorResponse(errors, status = 400)`** *(dev. 25)*. The failure rule takes the status from the error, so `failWith` stores it there. One-field pattern matches need `_`. It renders through an internal `SafeError` (one entry per message, no path). `derives JsonEncoder` is kept and now includes `status`.
- **A finalizer failure is not part of the approval** *(issue #40)*. `ZioRequest` closes the ZIO request scope after the program and logs a failed close through `ZioCall.logFinalizerFailure`, the same log line that the streamed path already used. The failure is never combined with the program's `Exit` (it was `cause ++ closeCause` before), so a dying finalizer can neither replace a success nor turn an approved `SafeError` into a defect. `HandlerBoundarySpec` ("a dying request-scope finalizer is logged and keeps the computed response") and `HandlerBoundarySuite` (the Kyo test of the same shape) prove that both runtimes give the same responses. "a release that dies inside the handler's own scope follows the runtime's own rules", in both suites, documents the inner-scope difference: ZIO answers 500, Kyo logs and keeps 200.
- **ZIO exit mapping** *(dev. 27)*. A single `Fail` of `SafeError` or `ErrorResponse` stays typed and approved, also when the cause only adds interruptions (for example `zipPar` siblings). A single `Fail` of another throwable is a typed failure of an internal `ZioDefect` with `Cause.prettyPrint` (not the raw error), so diagnostics keep ZIO traces. Several failures, any defect, or interruption alone are a panic `ZioDefect`. Both render as the same opaque 500.

### 9.6 Lifecycle and cancellation

<!-- claude 2026-10-19: changed: the "ZIO request scope" item: value and Unit bodies log a dying finalizer (issue #40) -->

- **`KHandler.uninterruptible(step)`** *(D-1, R1, AD-10)*: `Scope.acquireRelease(Fiber.initUnscoped(step))(_.getResult.unit)`, then `fiber.uninterruptible.map(_.get)`. The release joins the request scope in the same step that starts the fiber, so an interrupt cannot land between the two, and the request waits for the step. Tested in both runtimes, including a case that shows bare `Async.uninterruptible` settling too early.
- **Forked Kyo work is awaited** *(D-2, R3 disproved)*. The review expected Kyo to settle before cleanup in work forked by `Async.timeout`, `race`, `gather`, `zip` and a concurrent `foreach`. A test with a 300 ms asynchronous release shows the cleanup finishes first; it stays as a regression test. Only unowned fibers (`Fiber.initUnscoped` without a scope) are not awaited.
- **`Shared` registers its close hook after a successful acquisition** *(dev. 13)*, not at construction, because route modules can load before Nitro plugins run. `close` returns to idle, and a later `get` acquires again. Waiting promises are uninterruptible, so one cancelled request cannot cancel a shared acquisition.
- **`close` during the first acquisition** *(D-14, R7)* makes the waiting `get`s fail with `IllegalStateException` instead of handing them a released value. The Nitro hook cannot trigger this, because `Shared` registers only after success; an explicit `close` can.
- **ZIO request scope** *(dev. 26, D-13, R4, issue #40)*. Value and `Unit` bodies close the ZIO scope inside the fiber. Until `0.2.0-alpha.1`, a dying ZIO finalizer was a 500 even after a success; since the next release (provisionally `0.3.0-alpha.1`) it is logged and the program's `Exit` is kept (see 4.7). This applies to streaming handlers too, which send their stream inside the program. <!-- claude 2026-10-20: changed: streams are sent inside the program (ADR-0004) --> On a client disconnect, the fiber also logs a failed close and keeps the interrupt as its `Exit`; a finalizer that ends in interruption is logged on these in-fiber paths, while `closeScope` after `stop` skips an interruption alone. The scope is created on the Kyo side and always closed after `stop`, because an interrupt inside the mask could otherwise drop the `Exit` that carried it. `ZioRequest.run` has an `afterProgram` test seam for that case.

### 9.7 Streaming

- **Demand-gated Kyo bridge** *(dev. 6)*; see [section 8](#8-streaming).
- **String streams only** *(D-3, R6, AD-1; replaces dev. 5)*. The first version accepted any element with a `NativeConverter` (and needed `Tag[A]` for Kyo's `Emit`). Node's `res.write` throws for objects and numbers, and the failure surfaced as an `Interrupted` 500 that kept the success head. `null` elements now fail the stream.
- **ZStream bodies** *(dev. 28, superseded on 2026-10-20 by item 27)*. `0.2.0-alpha.1` had body instances for `ZStream[Any, E <: Throwable, String]` and a separate one for `ZStream[Any, Nothing, String]`, because type inference does not pick `Nothing`. Streams are no longer bodies: `zioh3.Response.sendStream[E <: Throwable](stream: ZStream[Any, E, String])` takes both, because `E` is a type parameter of the method, and `ZResponseBody` has a `Sent` instance instead. <!-- claude 2026-10-20: changed: dev. 28 superseded (ADR-0004) -->
- **The head at the call and a failing step** *(item 27)*. The prototype of the spike wrote the head lazily over a socket and gave `Sent` after a stream failure. The implementation writes and flushes the head at the call (Q1) and fails the step (Q3); the spike memo records the measurements ([`spike-sendstream-fetch.md`](spike-sendstream-fetch.md), sections 2 and 5). <!-- claude 2026-10-20: created: the deviation from the spike prototype -->
- **Only a scope inside a Kyo handler sees a stream failure** *(item 27, review of the sendStream pull request)*. [ADR-0004](adr/0004-streams-are-sent.md) Q3 says that a transaction or a `Scope` finalizer around the stream sees the failure. In Kyo that holds for a `Scope.run` (or a transaction) that the handler writes around `sendStream`. A finalizer registered directly in the request scope gets `Absent` after any program failure, a stream failure included, because the request scope belongs to the supervisor (see section 8). ZIO's request `Scope` sees the failing `Exit`. The streaming guide tells Kyo users to write the `Scope.run`, and one test per runtime pins the behavior: "after a stream failure, a finalizer of the request scope gets Absent, and one of a Scope.run around sendStream gets the failure" (`HttpStreamSuite`) and "a stream failure after the head: a finalizer of the request Scope sees the failure" (`HttpStreamSpec`). Closing the Kyo request scope with the program's outcome would change what every request-scope finalizer sees on a failure and on a disconnect, so it needs its own decision. <!-- claude 2026-10-20: created: the Kyo request scope does not see the outcome -->
- **The producer starts after the head** *(item 27, review of the sendStream pull request)*. The first port created the producer fiber before writing the head and registered its release only after the head was written, so a head that could not be written left an idle producer and its channels behind. The producer is now created after the head, by `Scope.acquireRelease`. Tests: "a head that cannot be written fails the sendStream step before the stream starts, and the failure rule answers" and "a head that fails after part of it left closes the connection, and the stream never starts" (`HttpStreamSuite`), and the ZIO counterpart of the first in `HttpStreamSpec`. <!-- claude 2026-10-20: created: the producer is created after the head -->

### 9.8 Configuration, the Nitro plugin and the Nuxt module

- **Plugin layout** *(dev. 12)*. `runtime/context.mjs` builds the frozen records with no Nitro import; `runtime/h34s.mjs` wires `nitropack/runtime` and `import.meta.dev`. Nitro bundles the plugin itself (it is in `externals.inline`), so `nitropack/runtime` resolves to Nitro's own runtime and `import.meta.dev` reflects the build mode.
- **Debug flag** *(D-16, SEC-2; replaces part of dev. 21)*. The module declares `runtimeConfig.h34s.debug: false` and never reads Nuxt's `debug` option. Nuxt resolves `debug: true` to an object, and the `DEBUG` environment variable can set it during builds, so deriving the flag from it either never worked or could turn on diagnostics by accident. Merge rule: an absent `runtimeConfig.h34s` becomes `{ debug: false }`; an object without `debug` gets `debug: false` added; an existing `debug` or a non-object value is left alone (and anything but `true` means off).
- **`ZShared`** *(dev. 30)*. It adds `close: UIO[Unit]`; `layer` takes the `Runtime` as a `using` parameter; a single typed build failure keeps its own error. `provide` relies on the expected type to infer `R1`, which works in `ZHandler.define(request => shared.provide(...))`.
- **`ZHandler.define(options)` returns `ZHandler.Configured`** *(dev. 24)*. Scala forbids default arguments in more than one overloaded alternative, and both need `(using runtime: Runtime[Any] = Runtime.default)`. The call shape `ZHandler.define(options)(request => ...)` is the same. `define(defaults: ResponseHead)` is not offered; pass `HandlerOptions(defaults = ...)`.

### 9.9 Storage

- **`KHubKv.mount(base)` and `ZHubKv.mount(base)`** replace constructors that took a native object from TypeScript *(dev. 11, dev. 22)*. A startup record without a `storage` function (an outdated plugin) is `ConfigError("h34s Nitro plugin is not installed")`. A non-object mount is `ConfigError("Nitro storage mount '<base>' is not available")`. A throwing `storage` function is a panic. `KHubKv.fromNative` stays `private[exts]` for the ZIO adapter.
- **`StoredValueError(path)`** *(D-10, R5, SEC-1, AD-11)*. Every decode failure of a stored value becomes this one internal error. It names only the failing path, never the key, the value or the original exception, because native-converter's error message contains the value and the whole stored document, and keys are often session ids. An encoder `SafeError` becomes an `IllegalStateException`. Before this fix, a stored value that failed a schema answered an approved 422 and logged the stored data.

### 9.10 Kyo NativeSchema bridge

- **Decode uses the schema's structure as a shape hint** *(B2-1)*. kyo-schema RC7's `StructureValueReader` truncates fractions and wraps out-of-range integers, and its Instant, Duration and base64 parse errors escape as panics (500). The hint makes reads as strict as kyo-schema-json: whole numbers in range for `Int`/`Short`/`Byte`; `"NaN"`, `"Infinity"` and `"-Infinity"` strings for `Double`/`Float`; typed Instant, Duration, bytes, `BigInt` and `BigDecimal` values; a parse failure is `InvalidInput()`. It finds fields and variants by Scala name, `@rename`, `@alias`, external tagging and `@discriminator`. Where it cannot locate a value (fluent `Schema.rename`, `renameAllFields`, fluent `.discriminator`, adjacent, tuple or untagged unions), the value is converted generically and kyo's lenient number coercion applies. A custom `Codec.Reader` is impossible outside `package kyo`.
- **Number policy** *(B2-2)*. A `Long` decodes only from a safe integer (absolute value up to 2^53-1), else `InvalidInput()`; a larger `Long` encodes as `JSON.parse` would read it (lossy). A `Float` is written with its shortest decimal when the hint names it. Decode also accepts JSON numbers for `BigDecimal` and whole numbers for `BigInt`; encode always writes strings.
- **Big-number limits** *(D-12, SEC-4)*. Strings longer than 1000 characters and `BigDecimal` exponents beyond +/-10000 are malformed (422) before parsing. Scala.js's `BigInteger` parse is quadratic, so one 1 MiB digit string was estimated (from the algorithm, not measured) to block the event loop for seconds.
- **Known wire differences from `JSON.parse(Json.encode(x))`** *(B2-3)*, not covered by tests: with fluent transforms, kyo-schema-json writes `BigNum` unquoted while the bridge writes a string; a `Float` the hint cannot locate is written as its double; lone UTF-16 surrogates; a `String`-keyed map bound to the pair-array instance is written as an object.
- **Validation scope** *(B2-4)*. Only the root schema's checks run (`Schema.validate` semantics). Put checks on the root with focus paths (`_.customer.name`). The entry path is the check's segments joined with `.`; a root `check(pred, msg)` has no path.
- **A checked model does not `derives Schema`** *(B2-5)*. kyo-schema has no check annotations, so the model declares `given Schema[X] = Schema[X].check(...)` in its companion and `derives NativeSchema`. It is still one schema. The Kyo example's `GreetingRequest` does this; `Greeting` uses `derives Schema, NativeSchema`.
- **Dependencies** *(B2-6)*. The bridge depends on kyo-schema and `h3-ext` only. kyo-schema-json and kyo-schema-yaml are test dependencies.
- **What counts as a shape error (generic 422)** *(B2-7)*: non-plain objects (Date, class instances), functions, nesting deeper than 512, invalid ISO, base64 or decimal strings, and every kyo `DecodeException` (missing field, type mismatch, unknown field under `denyUnknownFields`, a rejecting `derivedVia` constructor). A key whose value is `undefined` is absent. Any other exception from `Structure.decode` is a defect.
- **Bundle size** *(B2-8)*. With kyo-schema, the Kyo example's fastLink internal module grew from 6.1 MB to 8.0 MB.
- **`secretSchema`** *(D-11, AD-3)* is a top-level given in both bridges.

### 9.11 ZIO NativeSchema bridge

- *(dev. 32)* `trait NativeSchema[A] extends NativeConverter[A]` with `derived(using Schema[A])`. `apply(schema): Codec` is kept and gains `validate: Either[InvalidInput, A]`; `Codec.converter` throws `InvalidInput`. zio-schema has no app-authored messages, and its `ValidationError.message` quotes the input, so entries use messages built from the rule and its bounds (`must have at least 3 characters`, `must be greater than 17`) with paths (`lead.name`, `members[1].name`). Negated validations get the non-negated wording. Transform rejections are shape failures. Field and element results accumulate.
- The bridge supports `String`, `Boolean`, `Int` and `Double` primitives, records, options, sequences, transforms and lazy schemas. Any other shape (enums, sealed traits, maps, other primitives) fails with `UnsupportedSchema`, a defect.

### 9.12 Examples

<!-- claude 2026-10-20: changed: links to the guides' new paths under docs/content/ (issue #25) -->

- **ZIO example** *(dev. 33, D-17, F1, AD-12)*. A Skunk pool (`Session.Builder.pooled(4)`, explicit `Network.forAsync`) lives in a `ZShared` layer. Each request takes one pooled session for its request scope on first use (memoized in the request scope), so input validation runs before a database session is needed. Settings come from `runtimeConfig.database` (`NUXT_DATABASE_*`, the password a `Secret`, empty means none). Models are split: `NoteRow` (private SQL row), `Note` (domain), `NoteView` (wire). `server/plugins/skunk-node-crypto.ts` sets `globalThis.require` because Skunk's SCRAM code calls `require('crypto')` from ES module output.
- **Kyo example** *(D-17, AD-7)*. It reads `runtimeConfig.greeting` with `RuntimeConfig.load` inside a `Shared`, and `GreetingRequest` validates "not blank, at most 80 characters" in its schema.
- **Build heap.** The example links a Kyo and a ZIO application, and the scripts pass `--max-old-space-size=8192`. Measured on 2026-10-19 ([#30](https://github.com/yuri4n/h34s/issues/30)): Nitro 2.13.1 bundles the linked Scala.js and its source maps twice in one process, once for the prerendered Nuxt Content route and once for the server. Without the prerendered route, without server source maps, or with one application, the build passed with a 4096 MiB heap limit (peak RSS 3.4 to 4.5 GiB); with all three it needs more than 4 GiB (6144 MiB passed once). The maintainer delegated the remedy. The decision: an opt-in option of the Nuxt module, `scalaPrerender: 'stub'`, which resolves every import of a configured Scala.js output to one stub in Nitro's prerender Rollup build only (`nitro:init`, then `prerender:init`, then `rollup:before`). The stub's exports are h3 handlers that answer 501, so the option is opt-in (they throw an error class with h3's `__h3_error__` marker, so h3 and Nitro treat the 501 as a handled error and keep its message): a page that calls a Scala.js route while Nuxt prerenders it gets that 501. A page that catches it, as `useFetch` does, still prerenders with status 200, so Nitro's check of failed routes passes. The stub therefore logs each request and records it in a process-global list (Nitro imports the prerender bundle into the build process); the module opens the list at `prerender:init` and, at `prerender:done`, stops the build with the list when `prerender.failOnError` is on (Nuxt's default) or warns otherwise. Nitro 2.13.1 calls `prerender:done` before its own check of failed routes, so when that check is about to stop the build, the module logs the list as an error and lets Nitro throw with its summary. `scripts/check-npm-nuxt.mjs` checks the option in a real build of the packed module. The default, `'bundle'`, keeps the earlier behavior. The example uses `'stub'`: its build then passed with a 4096 MiB heap limit (peak RSS 4.05 and 4.07 GiB in two builds). With an equivalent temporary hook, it ran out of memory at Node's 2240 MiB default on a 15.7 GiB cloud VM. See [compatibility](../content/2.reference/3.compatibility.md#heap-measurement-2026-10-19). <!-- claude 2026-10-19: changed: the measured cause of the build heap, the scalaPrerender decision, the request report and the real-build check (review F1, F2, F3); the handled 501 and Nitro's own failure (second review F1, F2) -->

## 10. Review findings and their outcome

PR #14 went through a four-part adversarial review (runtime, security, API and docs, build). A second reviewer checked each finding before the fix pass. Of the 34 confirmed findings, 32 were fixed (with regression tests where the fix changed behavior), one was disproved by a test (R3), and one was kept by design (the ZIO half of AD-9).

| Area | Findings | Outcome |
| --- | --- | --- |
| Runtime | R1 `Async.uninterruptible` did not delay the settle; R2 absent body was fatal in fastLink builds; R3 forked cleanup; R4 ZIO stream scope leak; R5 KV converter `InvalidInput` approved; R6 non-string stream elements; R7 `close` during acquisition; R8 ended request stream hung | All fixed except R3, which a test disproved (see 9.6) |
| Security | SEC-1 KV decode errors logged stored data; SEC-2 Nuxt `debug` never enabled diagnostics; SEC-3 raw values reached h3's sniffing; SEC-4 unbounded big numbers; SEC-5 `Secret` decode messages; SEC-6 cookie deletion without `Secure`; SEC-7 unpinned dependencies with a token; SEC-8 bodies without a content type | All fixed; SEC-8 as `requireJsonContentType` (opt-in in `0.2.0-alpha.1`, on by default since `0.3.0-alpha.1`, issue #41) plus a "cross-site requests" note |
| API and docs | AD-1 to AD-13: stream element types, `nuxt dev` evidence, `Schema[Secret]`, npm version, validation timing, cookie options, the Kyo demo, `Request.fake` config, `failWith`, uninterruptible docs, `Secret` decode messages in KV logs, models at boundaries, the disconnect rule in `AGENTS.md` | All fixed; AD-9 fixed for Kyo, kept for ZIO by design |
| Build | B1 release notes checked late; B2 npm version; B3 stale "not verified" rows; B4 `scala:types`; B5 stale Bruno collection; B6 missing redirect; B7 locale database size | B1-B5 fixed; B6 and B7 refuted (no deployed site; Skunk's minimal locale database shadows the full one in the ZIO demo) |

## Appendix: facts from research that still hold

These facts come from reading library sources and from experiments during the refactor. The code depends on them. Re-check them when you upgrade the library named in the source column.

### Kyo 1.0.0-RC7

<!-- claude 2026-10-20: changed: the merged Env, naming and outcome-aware Scope.ensure facts (ADR-0004, ADR-0006) -->

| Fact | Source |
| --- | --- |
| `Abort.run[Throwable]` (and `Abort.catching[Throwable]`) turns every *thrown* exception into a typed `Failure`, because the handler checks `ConcreteTag[Throwable].accepts(ex)`. Only `Abort.run[Any]` keeps thrown exceptions as `Panic`. | kyo-prelude `kyo/Abort.scala` (`runWith`, `catching`) |
| `Abort.recover` does not roll back `Var` changes made before the failure. h34s runs `Var` outside `Abort` so the failure renderer still sees the head. | kyo-prelude `kyo/Abort.scala`, `kyo/Var.scala`; h34s `KHandler.scala` |
| `Env`, `Local` and `Scope` are context effects and cross forks. `Var`, `Emit` and `Memo` need an explicit `Isolate` to cross `Fiber.init`, `Async.timeout`, `race` and friends, so code that forks while it needs `Var[ResponseHead]` does not compile without one. | kyo-kernel `kyo/kernel/Isolate.scala`; kyo-core `kyo/Fiber.scala`, `kyo/Async.scala` |
| `Async.uninterruptible` runs the step on an unowned fiber (`Fiber.internal.initUnscoped`) and joins an uninterruptible mirror. An interrupted caller settles at once and its scope closes while the step still runs. | kyo-core `kyo/Async.scala`, `kyo/Fiber.scala`, `kyo/internal/IOPromise.scala`, `IOTask.scala` |
| Scope finalizers run once, in reverse registration order. Finalizer failures are logged, not thrown. `Scope.run` waits for the drain. `Fiber.init`'s release interrupts the fiber and awaits its scope. | kyo-core `kyo/Scope.scala`, `kyo/Fiber.scala` |
| Inside an h34s handler, work forked with the structured combinators (`Async.timeout`, `race`, `gather`, `zip`, a concurrent `foreach`) finishes its asynchronous cleanup before the request settles. Only unowned fibers are not awaited. | h34s `HttpHandlerSuite` (regression test for R3) |
| Kyo has no `js.Promise` interop on Scala.js. `Async.fromFuture` turns a rejected future into a `Panic`, not a typed failure. h34s bridges promises itself. | kyo-core `kyo/Async.scala`, `kyo/internal/AsyncPlatformSpecific.scala` |
| `Emit.runFirst`, `Poll.runFirst`, `ArrowEffect.handleFirst` and `Stream.splitAt` are `private[kyo]`, and `splitAtWith` keeps the rest under `Region.NoEscape`. A stream cannot be stepped from outside; use one producer fiber and a channel. | kyo-prelude `kyo/Emit.scala`, `kyo/Poll.scala`, `kyo/Stream.scala`; kyo-kernel `ArrowEffect.scala` |
| `Channel.Unsafe.init(capacity)` rounds a positive capacity up to a power of two; capacity 0 or less is a rendezvous channel. | kyo-core `kyo/Channel.scala` |
| Fatal errors are `VirtualMachineError` or `ControlThrowable`. Kernel handlers skip them; `IOTask.run` completes the fiber with a `Panic` and rethrows into the macrotask. | kyo-data `kyo/IsFatal.scala`; kyo-core `IOTask.scala` |
| `Frame` cannot be derived inside a package named `kyo`, so h34s code stays in `dev.yuri4n...` and takes `(using Frame)`. | kyo-data `kyo/Frame.scala` |
| `Promise.init[E, A]` names its type parameters `E, A` but they mean value and effects: call `Promise.init[Value, Abort[Err]]`. | kyo-core `kyo/Fiber.scala` |
| With two `Env` entries in an effect alias, `Env.run(service)(program)` cannot reduce the remaining pending set (`?1.SReduced` from `kyo.internal.Reducible`), so `KHandler.define(request => Env.run(config)(program(request)))` stops compiling. One entry with an intersection type, `Env[Fetch & HandlerTransport]`, compiles, and two nested `Env.run` calls with the two values eliminate it. | kyo-prelude `kyo/Env.scala`, `kyo/internal/Reducible.scala`; spike memo, section 2.7 |
| `kyo.Exchange` exists, so a public `kyoh3.Exchange` resolves to Kyo's under `import kyo.*`; the transport is named `HandlerTransport`. A definition named `$fetch` warns `E230` in Scala 3.9.0; `@annotation.nowarn("id=E230")` at the definition silences it, and call sites do not warn. | kyo-core; Scala 3.9.0; spike memo, section 2.7 |
| `Scope.ensure` has an overload that receives the outcome (`Maybe[Result.Error[Any]]`, `Absent` on success). A `Scope.run` inside a handler passes it an `Abort` failure of its body, and a client disconnect still waits for its asynchronous finalizers. | kyo-core `kyo/Scope.scala`; h34s `HttpStreamSuite` |
| `Layer.run` memoizes only within one `Memo.run`. Running layers per request rebuilds them per request; build application services once. There is no `Layer.scoped`. | kyo-prelude `kyo/Layer.scala`, `kyo/Memo.scala` |
| Kyo artifacts pull `locales-full-currencies-db` (about 40 MB of fastLink output). Applications can exclude it. Skunk brings `locales-minimal-en_us-db`, which shadows the full database by classpath order in the ZIO demo. | kyo-data POM; skunk-core 1.0.0-M12 POM |

### kyo-zio 1.0.0-RC7

| Fact | Source |
| --- | --- |
| Three public objects only: `ZIOs`, `ZLayers`, `ZStreams`. It pins ZIO 2.1.26 and needs Scala 3.9.0. | kyo-zio sources and POM |
| `ZIOs.get` uses `zio.Runtime.default`, rejects `ZIO[R, ...]` with `R != Any` at compile time, and on Kyo interruption calls `fiber.unsafe.interrupt` without waiting for ZIO finalizers. Causes flatten to the first error; ZIO interruption becomes `Panic(Interrupted)`. | kyo-zio `kyo/ZIOs.scala` |
| `ZIOs.run` (Kyo inside ZIO) interrupts the Kyo fiber and waits for it on ZIO interruption. Kyo `Panic` becomes `Exit.die`, also for a Kyo-side interruption. | kyo-zio `kyo/ZIOs.scala` |
| `ZStreams.get` closes the ZIO stream scope from an inner `Scope.run` and pulls with `ZIOs.get` (not awaited on interruption). `ZLayers.run` accepts no `Scope`, so a Kyo resource layer cannot become a scoped `ZLayer` with it. | kyo-zio `kyo/ZStreams.scala`, `kyo/ZLayers.scala` |

### kyo-schema 1.0.0-RC7

| Fact | Source |
| --- | --- |
| Checks run only when `Schema.validate` is called, and only the root schema's checks. There are no check annotations. | kyo-schema `kyo/Schema.scala` |
| `StructureValueReader` is lenient on numbers (truncates fractions, wraps out-of-range integers) and lets Instant, Duration and base64 parse errors escape as panics. The JSON reader is strict. | kyo-schema `StructureValueReader`; kyo-schema-json `JsonReader.scala` |
| `Codec.Reader` has an abstract `private[kyo]` member (`requireEndOfInput`), so no custom reader can live outside `package kyo`. `Structure.encode` and `Structure.decode` are public. | kyo-schema `Codec.scala`, `Structure.scala` |
| kyo-schema-json requires strings for `BigInt` / `BigDecimal` and parses them without a length limit. | kyo-schema-json `JsonReader.scala` |

### kyo-sql and kyo-net 1.0.0-RC7

| Fact | Source |
| --- | --- |
| The connection URL needs an explicit port and a database path; otherwise `SqlConnectionUrlParseException`. | kyo-sql `kyo/SqlConfig.scala` (`Url.parse`) |
| The URL parser does not percent-decode user or password. It splits the query at the first `?`, the user information at the *last* `@`, and user from password at the first `:`. So a user with `:` or `?`, a password with `?`, or a database name with `@` or `?` cannot be expressed. yurian.land validates and decodes its URL before it builds the kyo-sql URL. | kyo-sql `kyo/SqlConfig.scala`; yurian.land `DatabaseUrl` |
| `sslmode=allow`, `prefer` and `require` encrypt without verifying the certificate or host name. `verify-ca` / `verify-full` need `sslrootcert`, a file path. `SqlConfig.tls(NetTlsConfig.default)` verifies against the system store. | kyo-sql `kyo/internal/tls/TlsContext.scala`, `kyo/SqlConfig.scala` |
| `DB.run(url)` opens and closes a pool per call. Build one client per application (`Shared(PostgresClient.init(url, config))`) and use `DB.run(client)` per request. | kyo-sql `kyo/DB.scala` |
| Transactions: a failed statement marks the transaction, and a body that recovers still rolls back. A non-SQL `Abort` in the body also rolls back. An interrupted transaction gets `cancelTimeout` (default 2 s) to cancel and roll back. | kyo-sql `kyo/SqlClient.scala` |
| `SqlConfig.default` has `idleTimeout` 10 minutes. The pool closes idle connections without sending `Terminate`, so "unexpected EOF" lines in PostgreSQL debug logs do not show an ungraceful shutdown. kyo-net sets no TCP keepalive. | kyo-sql `SqlConfig`, `SqlConnectionPool.scala`, `PostgresConnection`; kyo-net sources |
| On Scala.js, kyo-sql-postgres registers its backend with `@JSExportTopLevel("__kyo_sql_postgres_init")`, which adds a top-level export (a `main.js` module) to the linked output. | kyo-sql-postgres `PostgresBackendRegistration.scala` |
| kyo-net picks its Node `net`/`tls` backend when koffi is absent (it logs one warning); `KYO_NET_BACKEND=node` forces it. yurian.land ran kyo-sql against PostgreSQL 16 inside a Nitro node-server production build this way. Other presets are untested. | kyo-net `kyo/net/NetFlags.scala`, `IoBackendPlatform.scala`; yurian.land PR #15 |

### Nitro, Nuxt and h3

<!-- claude 2026-10-20: changed: the sendStream, local-fetch and preset facts of the spike (ADR-0004, ADR-0008) -->

| Fact | Source |
| --- | --- |
| `useRuntimeConfig()` without an event returns a frozen copy with the environment applied once at startup. With an event it deep-clones the config, applies the environment again, and caches the result on `event.context.nitro.runtimeConfig`. | nitropack 2.13.1 and 2.13.3 `dist/runtime/internal/config.mjs` |
| `event.context.nitro` exists before Nitro's `request` hook runs, so a plugin can call `useRuntimeConfig(event)` there. | nitropack `dist/runtime/internal/app.mjs` |
| `NUXT_*` overrides work only for keys declared in `runtimeConfig`. Nested keys map to `snakeCase(path).toUpperCase()` joined by `_`. Values are parsed with `destr`. | nitropack `utils.env.mjs`; `@nuxt/schema` (sets `envPrefix: "NUXT_"`) |
| `useStorage(base)` is `prefixStorage(storage, base)` over a build-time virtual module that Scala.js cannot import. unstorage's `Storage` also has the aliases `get`, `set`, `has`, `del`, `keys`. `{ ttl }` works only with drivers that implement it. | nitropack `dist/runtime/internal/storage.mjs`; unstorage 1.17.3 types |
| The node-server preset calls the `close` hook on `SIGTERM` / `SIGINT` and then exits. `nitro dev` calls it when it replaces a worker. | nitropack node-server and `nitro-dev.mjs` runtimes |
| Nitro uses only the default export of a handler module. Programmatic `nitro.handlers` entries are typed like scanned files when a `.d.ts` sits next to the module. | nitropack `rollup/index.mjs`, `core/index.mjs` |
| Nuxt resolves `debug: true` to an object, never the boolean `true`, and the `DEBUG` environment variable can set it. | `@nuxt/schema` 4.3.1 |
| h3 sends a `null` handler result as 204 (`sendNoContent`). In a plain `createApp`, `undefined` falls through to the next handler; in a preemptive router (Nitro) it becomes `null`. A string is sent as `text/html`; an object with a `buffer` key is sent as bytes. | h3 1.15.4 / 1.15.5 `handleHandlerResponse` |
| h3's `readBody` parses JSON and URL-encoded bodies whatever the content type, with no size limit. `deleteCookie` sends only the options you pass (no `Secure` by default). | h3 1.15.4 `dist/index.mjs` |
| h34s sets h3's private `_handled` field on a disconnect so h3 sends no fallback 404 for a response h34s ended. It is tested with h3 1.15.4; keep h3 pinned. | h34s `RequestLifecycle.scala` |
| h3's `sendStream` stores the stream in `res._data`. Without `res.socket` it sets `_handled` and resolves at once without reading the stream; with a socket it pipes a Web stream into `res.write`, ends the response after the last chunk, and never calls `flushHeaders`. It has no close or abort wiring for Web streams. | h3 1.15.4 / 1.15.5 `sendStream`; spike memo, sections 2.1 and 2.2 |
| Nitro's `localFetch` answers a path that starts with `/` in-process through node-mock-http, whose mock response has no socket, and copies status, headers and `res._data` when the h3 handler promise settles. So a head written later is lost, and a Web `Response` body accepts only `Uint8Array` chunks. No in-process response carries `content-length`. In-process, nothing signals a disconnect; only `ReadableStream.cancel` on the body reaches the callee. | nitropack 2.13.1 `dist/runtime/internal/app.mjs`; node-mock-http 1.0.4; spike memo, sections 2.1 to 2.4 and 2.6 |
| Many Nitro presets (Cloudflare, Deno, Bun, Vercel Edge, Netlify, AWS Lambda) serve every request through the socketless local call; the node-server preset and the dev worker use `node:http`. No preset was built to check streaming. | nitropack 2.13.1 presets; spike memo, section 2.2 |

### Node, ZIO and other libraries

| Fact | Source |
| --- | --- |
| Node's `res.write` throws `ERR_INVALID_ARG_TYPE` for objects and numbers and `ERR_STREAM_NULL_VALUES` for `null`. | Node 24.19.0, checked with a plain `http` server |
| After a request body is consumed, new listeners never fire and the stream shows `readableEnded: true`. `complete` can be `true` on a fresh request whose small body already arrived. | Node 24.19.0 experiment |
| native-converter's `ParseState.fail` builds its message from `JSON.stringify` of the failing value and of the whole input document. Never log or return it. | native-converter 0.9.0 `ParseState.scala` |
| Under fastLink (checked semantics: tests, `nuxt dev`), that message path fails with a fatal `UndefinedBehaviorError` when the value is `undefined`. Under fullLink (unchecked casts, production) the same input gives an ordinary error whose message contains `undefined`. Behavior can differ between development and production; test with both. | native-converter 0.9.0; Scala.js 1.22.0 linker semantics |
| A derived native-converter product reads a missing key as `null`, so a missing `String` field decodes to `null` and a missing `Int` to `0` instead of failing. Schema-backed converters do not have this gap. native-converter 0.9 has no `BigInt` converter. | native-converter 0.9.0 `NativeConverter.scala` |
| ZIO's `ZState` is backed by a `FiberRef`: a forked fiber works on a copy, and joining copies the child's state back. | ZIO 2.1.26 |
| On Scala.js, ZIO's default `reportFatal` prints and rethrows. A fatal error leaves the fiber without an `Exit`, so observers never run. | ZIO 2.1.26 `RuntimePlatformSpecific.scala`, `FiberRuntime.scala` |
| zio-json-yaml has no Scala.js artifact (`_sjs1_3` returns 404 on Maven Central). | Maven Central |
| `derives Schema` for ZIO Schema needs `import zio.schema.derived`. `ValidationError.message` quotes the input value. | zio-schema 1.7.5 |
| Scala.js's `BigInteger(String)` parse is quadratic in the number of digits. | scalajs-javalib 1.22.0 |
| Skunk 1.0.0-M12's SCRAM code calls `require('crypto')`, which ES module output does not define. | skunk-core 1.0.0-M12 |

### Tooling notes

- On macOS, Node tooling tests need `TMPDIR` set to its real path: the default `/var/folders/...` path is spelled `/private/var/...` after resolution, and five tests in `scripts/scala-dev-modules.test.mjs` fail on that. `check-npm-nuxt.mjs` needs a short `TMPDIR`, because Nitro's development socket path must stay under 104 bytes.
- A long-lived sbt server can run out of memory in `fullLinkJS` after many runs. A fresh server links again without trouble. Run one sbt process at a time.
- Inside an sbt `set` command, `%%%` resolves the JVM artifact. Edit `build.sbt` instead.
- An install with `--ignore-scripts` leaves `better-sqlite3` unbuilt and skips `nuxt prepare`. The example's Nuxt Content then needs the native SQLite connector (an ignored `examples/nuxt/.nuxtrc` with `content.experimental.sqliteConnector=native`) and an explicit `nuxt prepare`.
