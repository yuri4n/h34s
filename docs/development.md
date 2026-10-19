# Develop the Nuxt/Scala.js example

Run the optional integration demo in `examples/nuxt/` to explore both effect APIs. Its Scala application sources live in `examples/nuxt/scala/zio/` and `examples/nuxt/scala/kyo/`. The separate `website/` app serves the documentation. To use h34s in your own Nuxt project, follow [adoption.md](adoption.md).

The demo builds the h34s libraries from this repository's sources (version `0.2.0-alpha.1` in `version.sbt`), not from published packages. Node runs the Scala.js handlers inside Nitro; Java and sbt are build-time tools. The application itself is excluded from library publication. See [releases.md](releases.md) for the published private Maven coordinates.

The example consumes the local `@yuri4n/h34s-nuxt` package for build orchestration and Nuxt development integration. External applications install the published package that matches their Scala libraries: [`0.2.0-alpha.1`](https://github.com/yuri4n/h34s/releases/tag/npm-v0.2.0-alpha.1), or [`0.1.0-alpha.1`](https://github.com/yuri4n/h34s/releases/tag/npm-v0.1.0-alpha.1) for the older API. The [npm tooling guide](npm-tooling.md) explains how to configure the same integration without copying scripts.

## Prerequisites

Use Node 24, pnpm 10.34.4, Java 25, sbt 1.11.7, and Docker with Compose for the optional database example. The build selects Scala.js 1.22.0 and Scala 3.9.0 for every module, with Kyo 1.0.0-RC7 and ZIO 2.1.26. Kyo's macros need Java 25, and the ZIO modules build on the Kyo core, so Java 21 is no longer enough for any task. `project/build.properties` pins sbt; `.jvmopts` supplies the stack and heap sizes needed by the linked dependencies. The recovered Nuxt dependency set uses pnpm 10's build allowlist.

In a Claude Code cloud session, the repository's SessionStart hook installs Java 25, sbt, Node 24, and pnpm, puts them on `PATH`, and installs the pnpm workspace and the npm test fixtures; it does not start Docker. There, the Scala and Node checks below are verified, and so are the production build and the Notes routes against the VM's PostgreSQL 16 (see [the 2026-10-19 record](#cloud-session-production-build-and-postgresql-2026-10-19)). `pnpm dev` and the Docker Compose steps are not verified there. <!-- claude 2026-10-19: changed: production build and PostgreSQL now verified in a cloud session --> [maintainers/cloud.md](maintainers/cloud.md) explains the setup, the Maven Central mirror that sbt uses there, and the session's limits. On a normal development machine, use the installed `sbt` launcher.

## 1. Start from this repository

Clone the private repository using your existing GitHub access, then run these commands from the repository root. The root scripts delegate to the demo:

```sh
pnpm install --frozen-lockfile
pnpm run scala:fast
pnpm dev
```

Development loads the linked Scala.js ES modules directly in Node. Nitro watches both output directories and replaces its worker after relinking, keeping Node’s module cache fresh. This avoids the large Rollup graphs that exhausted an 8 GB heap during bundled watch rebuilds. The current `pnpm dev` script enables Node source maps automatically and needs no heap override. The application pins `h3` as a direct runtime dependency for the linked modules.

Make a direct HTTP request to `/api/test`; the response should be `abc`. This route needs no database. It streams its body, and streamed bodies are sent as they are. Value bodies use the default envelope instead: `/api/kyo/greeting` answers `{"data":{"message":"Hello, Kyo!"}}`. Scala source changes need another `nuxtsvr/fastLinkJS`; Nuxt watches the linked JavaScript. For continuous Scala linking, use `pnpm run scala:watch` in another terminal. The root scripts pass `examples/nuxt/h34s.build.config.mjs` to the CLI. That wrapper uses the demo's `h34s.config.mjs` and resolves its output paths from the repository root.

Nuxt development mode also enables detailed Scala error responses and maps Node stack traces back to `.scala` files. See the [debugging guide](debugging.md) for error details, stack traces, and attaching a Node debugger.

An earlier cloud environment used an ignored `examples/nuxt/.nuxtrc` to select Node's native SQLite connector for Nuxt Content and disable remote font providers. Those settings addressed that environment's SQLite addon startup and network limitations. They do not affect the Scala database layer. The production build without the native SQLite connector or font settings in `.nuxtrc` passed in a cloud session on 2026-10-19. <!-- claude 2026-10-19: changed: cloud build verified -->

The independent [Kyo example](kyo.md) adds GET and POST `/api/kyo/greeting`. Its compiled output lives in `examples/nuxt/server/.scala-kyo`; link both applications before starting this combined Nuxt demonstration. In your own Kyo-only application, depend on `kyo-h3-ext`; you do not need the ZIO example or its database integration.

## 2. Run the model and database example

The ZIO Notes routes read their PostgreSQL settings from Nuxt `runtimeConfig.database` (see `examples/nuxt/nuxt.config.ts`). Nitro overrides each key at runtime from an environment variable: `NUXT_DATABASE_HOST`, `NUXT_DATABASE_PORT`, `NUXT_DATABASE_USER`, `NUXT_DATABASE_PASSWORD`, and `NUXT_DATABASE_NAME`. Docker Compose reads the same names. The defaults are host `127.0.0.1`, port `5432`, user `h34s`, and database `h34s`. Supply your own local password; do not commit it. Compose refuses to start without `NUXT_DATABASE_PASSWORD`.

The easiest way is a local environment file. Copy `examples/nuxt/.env.example` to `examples/nuxt/.env` (Git ignores it) and set the password there. Nuxt development reads that file from the example's application root. Add `--env-file examples/nuxt/.env` to each Compose command below. Instead of a file, you can export the variables in the shell you use for both Compose and Nuxt.

Earlier versions of this guide used `MY_APP_DB_*` variables. Rename them to the `NUXT_DATABASE_*` names; the example no longer reads the old ones.

Keep the default Compose project name `h34s` to reuse the existing `h34s_postgres_data` volume after the demo's move. If you previously used another project name, set `COMPOSE_PROJECT_NAME` to that name or add `-p <existing-name>` to each Compose command below.

```sh
docker compose --env-file examples/nuxt/.env -f examples/nuxt/docker-compose.yml up -d postgres
docker compose --env-file examples/nuxt/.env -f examples/nuxt/docker-compose.yml exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Compose mounts the example schema into PostgreSQL's initialization directory. PostgreSQL runs those scripts only when initializing an empty volume. For an existing development database, apply the additive example schema explicitly:

```sh
docker compose --env-file examples/nuxt/.env -f examples/nuxt/docker-compose.yml exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  < examples/nuxt/scala/zio/src/main/resources/sql/01-init.sql
```

Rebuild Scala.js, then start Nuxt with the same variables. Exercise the endpoints with:

```sh
curl --fail -H 'Content-Type: application/json' \
  --data '{"text":"My first note"}' http://127.0.0.1:3000/api/notes
curl --fail http://127.0.0.1:3000/api/notes
```

POST returns status 201 with `{"data":{"id":1,"text":"My first note"}}` for a new empty database. GET returns `{"data":[...]}` with the notes ordered by ID. The ID may differ when the database already contains rows. Every response has `Cache-Control: no-store`, the default that the example's `AppHandler` sets.

Blank text returns status 422 with `{"errors":[{"message":"must have at least 1 character","path":"text"}]}`. A body of the wrong shape, for example `{"text":42}`, returns 422 with one generic entry, `Request body does not match the expected shape`. Text that is not valid JSON returns 400. The entries never repeat the input.

`notes/Notes.scala` defines the application model, the request model, the service, and the Skunk implementation. Each model has one ZIO Schema and derives its native converter from it (`derives Schema, NativeSchema`). The `CreateNote` schema trims the text and checks its length, so the handler reads the body with `request.body.json[CreateNote]` and repeats none of those rules. See [schema.md](schema.md) for supported schema forms; plain `derives NativeConverter` models need no schema dependency.

The database pool is an application-scoped resource. `resources/SessionPool.scala` builds a Skunk pool (at most four sessions) from the startup runtime config, and the example's `app/AppHandler.scala` wraps it in `ZShared.layer`. The first request that needs it builds the pool; every later request reuses it. A Notes request takes at most one pooled session for its request scope, on first use (after the body was validated), and returns it when the response is complete. Nitro's `close` hook releases the pool. The Node server runs that hook on `SIGTERM` and `SIGINT`. These files belong to the example: write your own `AppHandler` and resource layers in your application.

`AppHandler` provides `Notes` before the handler runs, but the Notes layer takes a database session only on first use, after the body was validated. When PostgreSQL is unreachable, invalid input still answers 422, and valid input answers an opaque 500. <!-- claude 2026-10-19: changed: matches the session-on-first-use behavior above -->

Skunk's typed SQL interpolator encodes input values as bound parameters; it never concatenates them into SQL. Initialize the database schema during setup, not during requests.

The demo includes `examples/nuxt/server/plugins/skunk-node-crypto.ts`: the pinned Skunk version calls Node's `require('crypto')` for SCRAM authentication, so the plugin installs Node's loader before Nitro accepts requests. Add this plugin when adapting the database example to an ES-module application. PostgreSQL's default SCRAM authentication stays enabled. Restart Nuxt if authentication ran before the plugin was installed; the failed library initializer cannot recover in the same process.

## Production build

With the combined build toolchain selected, run:

```sh
pnpm build
```

This runs `pnpm run scala:full` before the demo's Nuxt production build. The packaged `h34s-scala build` step reads `examples/nuxt/h34s.build.config.mjs`, clears previous output from both `examples/nuxt/server/.scala` and `examples/nuxt/server/.scala-kyo`, runs `nuxtsvr/fullLinkJS` and `kyoExample/fullLinkJS`, and checks that both outputs exist. A failed link stops the build before Nuxt can package stale development code. Use `pnpm run scala:full` to run that step on its own. Nuxt writes the deployable output to `examples/nuxt/.output/`.

The example's `build` and `generate` scripts run Nuxt with an 8 GiB Node heap (`node --max-old-space-size=8192`). Keep that option for this example. The example also sets the Nuxt module option `scalaPrerender: 'stub'`, which keeps the linked Scala.js out of Nitro's prerender bundle; see [npm tooling](npm-tooling.md#keep-scalajs-out-of-the-prerender-bundle). With the stub, the build passed with a 4096 MiB heap limit. Without it, the build needs more than 4 GiB: with a 4096 MiB heap limit, it runs out of memory in "Building Nuxt Nitro server", with `FATAL ERROR: Ineffective mark-compacts near heap limit Allocation failed - JavaScript heap out of memory`, and a 6144 MiB limit passed in one run. Node's default heap limit depends on the machine: about 4 GiB on the maintainer's 16 GB Mac, and 2240 MiB on a 15.7 GiB Claude Code cloud VM. There, with `NODE_OPTIONS` unset and without the scripts' heap option, the build without the stub runs out of memory while it builds the prerender bundle. A build with an equivalent temporary hook in place of the option ran out of memory later, while it built the server output. 8 GiB keeps a margin. The 2026-10-19 cloud session's environment set `NODE_OPTIONS=--max-old-space-size=8192` (not this repository's hook), so a build there without the scripts' option still had an 8 GiB heap. <!-- claude 2026-10-19: changed: the measured cause of the 8 GiB heap and the scalaPrerender option (issue #30); the 2240 MiB run used a temporary hook (review F3) -->

<!-- claude 2026-10-19: created: the measured cause and its trade-offs (issue #30) -->
The cause is the size of the linked Scala.js, which Rollup processes twice in one Node process:

1. **Prerendering.** The example prerenders one route, Nuxt Content's `/__nuxt_content/focuses/sql_dump.txt`. For it, Nitro 2.13.1 builds a second, complete server bundle (the `nitro-prerender` preset) and imports that bundle into the build process. That step took 47 to 59 seconds for one small file across the baseline runs. With `scalaPrerender: 'stub'`, this bundle gets the stub instead of the linked Scala.js, and the step took 3.8 and 3.6 seconds in two builds. <!-- claude 2026-10-19: created: the step with the stub -->
2. **The server build.** Nitro then bundles the server again for the `node-server` preset.

Both bundles take in about 16.5 MB of linked JavaScript (11.1 MB for the ZIO application, 5.5 MB for Kyo) and, by default, its 13.2 MB of Scala.js source maps. Removing any one of three things let the build pass with a 4096 MiB heap limit (peak RSS 3.4 to 4.5 GiB): the prerendered route, the server source maps (`sourcemap.server=false`), or one of the two Scala.js applications. The full locale database made no difference: it is on the ZIO application's classpath, but excluding it did not change the linked output (same name, size and time stamp), so the linker does not include it. The [compatibility matrix](compatibility.md#heap-measurement-2026-10-19) has the measurements.

The example keeps all three: the Nuxt Content route, the server source maps for Scala stack traces in Node, and both applications. It sets `scalaPrerender: 'stub'` instead, because its one prerendered route runs no Scala.js code. On 2026-10-19, with the stub and a 4096 MiB heap limit, the example's `nuxt build` at commit `ae4c502` passed in 1 minute 18 seconds with a peak RSS of 4.07 GiB. The prerendered `sql_dump.txt` had the same SHA-256 as in a build without the stub, and no request reached the stub. On the built server, `smoke.mjs` with PostgreSQL, the `SIGTERM` session check and `kyo-smoke.mjs` passed. <!-- claude 2026-10-19: created: the example's stub and its measurement on the final build (review F3) -->

If your own build runs out of memory, set `scalaPrerender: 'stub'` when no prerendered page needs Scala.js, give the build the same heap option, or remove one of these causes from your application. Each has a cost: with the stub, a prerendered page that calls a Scala.js route gets a 501 response, and the build stops with a list of those requests; without server source maps, a production server started with `node --enable-source-maps` cannot map stack traces back to `.scala` files; and without prerendering, check that Nuxt Content still serves its data. <!-- claude 2026-10-19: created: the stub as a remedy and its cost, with the build error (review F1) -->

A production build needs a complete dependency installation. `pnpm install --frozen-lockfile` runs the install scripts of the packages that `pnpm-workspace.yaml` allows, including the `better-sqlite3` addon that Nuxt Content uses, and the example's `postinstall` step, `nuxt prepare`. An installation with `--ignore-scripts`, as the library checks use, skips both. `nuxt build` then fails with `Could not locate the bindings file` or with `failed to resolve "extends":"./.nuxt/tsconfig.json"`. To recover, run `pnpm install --frozen-lockfile` without `--ignore-scripts`. If pnpm answers `Already up to date`, as after the cloud session hook, it runs `nuxt prepare` but does not build the addon; then run `pnpm rebuild` too. <!-- claude 2026-10-19: changed: pnpm rebuild after an --ignore-scripts install --> If the addon cannot be built on your machine, select Node's built-in SQLite for Nuxt Content instead: put `content.experimental.sqliteConnector=native` in the ignored `examples/nuxt/.nuxtrc`, then run `pnpm --dir examples/nuxt exec nuxt prepare` before the build.

Start the built server from the example directory:

```sh
cd examples/nuxt
node .output/server/index.mjs
```

It listens on port 3000; set `NITRO_PORT` and `NITRO_HOST` (or `PORT` and `HOST`) to change that. The production server does not read `.env`. Pass `NUXT_DATABASE_*` in its environment. To include error causes and stack text in its 500 responses while you investigate a problem, start it with `NUXT_H34S_DEBUG=true`; keep that off on any server the public can reach. On `SIGTERM` or `SIGINT`, Nitro runs its `close` hook, which releases the shared database pool, and then exits.

This build entry point does not establish complete application packaging or a tested Nix deployment. The [release checks](releases.md#validate-and-publish-a-release) validate reusable library artifacts separately from packaging the example application.

## Checks

Run the library and example tests, link both applications, and run the tooling tests:

```sh
for fixture in scala-modules/exts/*/test-js; do npm ci --prefix "$fixture" --ignore-scripts; done
sbt 'h3Ext/test' 'kyoH3Ext/test' 'kyoNuxtHubExt/test' 'nativeConverterKyoSchemaExt/test' \
  'zioH3Ext/test' 'zioNuxtHubExt/test' 'nativeConverterZIOSchemaExt/test' 'nuxtsvr/test' \
  'nuxtsvr/fastLinkJS' 'kyoExample/fastLinkJS'
node --test scripts/*.test.mjs packages/nuxt/test/*.test.mjs
```

Then start the example (development or production) and run the HTTP smoke checks against it:

```sh
node scripts/kyo-smoke.mjs
node scripts/smoke.mjs
```

`kyo-smoke.mjs` needs no database. It checks the `{"data": ...}` envelope with the salutation from `runtimeConfig.greeting` (set `H34S_EXPECTED_SALUTATION` when you start the server with `NUXT_GREETING_SALUTATION`), `Cache-Control: no-store`, 422 entries with the `name` path for a blank or too long name, the generic 422 for a wrong shape and for an absent body, and 400 for text that is not JSON. `smoke.mjs` checks the stream, 422 entries with the `text` path, the generic 422 for null, missing, and incorrectly typed input, and 400 for text that is not JSON; these input checks need no database. It then checks a real insert and read with schema-normalized text, which needs the database and creates one note. Set `H34S_SMOKE_SKIP_DATABASE=true` to run only the input checks without PostgreSQL. `H34S_BASE_URL` selects another base URL; the default is `http://127.0.0.1:3000`.

### What was verified for this revision

On 2026-10-17, on macOS 15.7 (x86_64) with Node 24.19.0, Java 25, sbt 1.11.7, Nuxt 4.3.1, and Nitro 2.13.1:

- `pnpm build` linked both applications for production and built Nuxt. The production server (`node .output/server/index.mjs`) answered `GET /api/kyo/greeting` with `{"data":{"message":"Hello, Kyo!"}}` and `Cache-Control: no-store`. A blank name returned 422 with `{"errors":[{"message":"must not be blank","path":"name"}]}`, and an 81-character name returned 422 with `length must be <= 80` at the same path. `{"name":42}` returned the generic 422, invalid JSON returned 400, and a `text/plain` body returned 415. `/api/test` streamed `abc` with chunked transfer encoding. Unknown routes kept Nitro's own 404. `kyo-smoke.mjs` passed.
- Without a database, `/api/notes` returned an opaque 500 with no diagnostics. With `NUXT_H34S_DEBUG=true`, the same response carried `debug.cause` with the connection error and the Scala trace. The database password did not appear in any response or log.
- On `SIGTERM`, Nitro's `close` hook released the shared database pool that the first Notes request had registered, and the server exited with status 0.
- In `pnpm dev`, `/api/notes` without a database returned a 500 with `debug.cause`. A JavaScript error raised inside the linked Kyo handler had stack frames mapped to `RequestLifecycle.scala` and `KHandler.scala`, so the Scala.js source maps load in development.
- The Scala CI command passed: `h3Ext` 30 tests, `kyoH3Ext` 68, `kyoNuxtHubExt` 13, `nativeConverterKyoSchemaExt` 23, `zioH3Ext` 74, `zioNuxtHubExt` 13, `nativeConverterZIOSchemaExt` 19, and `nuxtsvr` 5, plus the compile-only modules, `releaseCheck`, and `check-release.mjs --allow-snapshot`. The 46 tooling tests passed. `check-consumers.mjs` built and served all four fresh consumers from a staged `0.2.0-SNAPSHOT`, and `check-npm-nuxt.mjs` passed with the packed module.

After the review fixes on the same day and machine (with the native SQLite connector in an ignored `.nuxtrc`, because the `better-sqlite3` addon was not built):

- `pnpm build` passed again. Against `node .output/server/index.mjs`, `kyo-smoke.mjs` passed with the default salutation and, after a restart with `NUXT_GREETING_SALUTATION=Hi`, with `H34S_EXPECTED_SALUTATION=Hi`. `H34S_SMOKE_SKIP_DATABASE=true node scripts/smoke.mjs` passed without PostgreSQL: invalid Notes input is now a 422 before any database work. `/api/test` carried `text/plain; charset=utf-8` and `X-Content-Type-Options: nosniff`.
- The Scala CI command passed: `h3Ext` 36 tests, `kyoH3Ext` 79, `kyoNuxtHubExt` 15, `nativeConverterKyoSchemaExt` 25, `zioH3Ext` 81, `zioNuxtHubExt` 14, `nativeConverterZIOSchemaExt` 20, and `nuxtsvr` 6, plus the compile-only modules, `releaseCheck`, and `check-release.mjs --allow-snapshot`. The 47 tooling tests passed. `check-consumers.mjs` (now installing the pinned h3 fixture with `npm ci`) passed for the four consumers.

Not verified on that day: the Notes routes against PostgreSQL. Docker was not running on the verification machine, so `smoke.mjs` stopped at its first database request. The next section records the database run. A client disconnect inside a running Nitro server was not exercised either; the Kyo test suite aborts a real HTTP client against h3 in Node. <!-- claude 2026-10-19: changed: points to the database run -->

Use direct HTTP for stream responses. In-process Nitro local fetches (`$fetch` during server rendering) were not tested with streamed Scala bodies.

Scala-to-TypeScript generation, complete application packaging, and Nix support remain pending.

<!-- claude 2026-10-19: created: cloud-session record for the production build and PostgreSQL (issues #29 and #30) -->
### Cloud session: production build and PostgreSQL (2026-10-19)

On 2026-10-19, in a Claude Code cloud session (Ubuntu 24.04.5, x86_64, 4 vCPUs, 15.7 GiB memory) with Node 24.22.0, pnpm 10.34.4, OpenJDK 25.0.4.1, sbt 1.11.7, Nuxt 4.3.1, Nitro 2.13.1, and PostgreSQL 16.15, from commit `f1fb8e1`; the kyo-sql run with `KyoSqlPostgresSuite` from commit `314546f`:

- `pnpm install --frozen-lockfile` and `pnpm rebuild` (the hook's install had skipped the `better-sqlite3` addon), then `pnpm run scala:full`: 2 minutes 14 seconds.
- The Nuxt build with the scripts' 8 GiB heap passed in 2 minutes 15 seconds. Its largest process peaked at 6.5 GiB RSS.
- PostgreSQL 16 ran with `password_encryption = scram-sha-256` and the default `host all all 127.0.0.1/32 scram-sha-256` rule. The role, database and schema came from [Start PostgreSQL and Redis](maintainers/cloud.md#start-postgresql-and-redis). The server started from `examples/nuxt` with the variables of `.env.example` and the password from `~/.h34s-db-password`.
- `node scripts/smoke.mjs` passed, including the database insert and read. With `curl`, `POST /api/notes` with `{"text":"My first note"}` answered `201 Created`, `cache-control: no-store` and `{"data":{"id":2,"text":"My first note"}}`. Blank text answered 422 with `{"errors":[{"message":"must have at least 1 character","path":"text"}]}`, and `{"text":42}` answered the generic 422. `GET /api/notes` answered 200 and listed both notes.
- The PostgreSQL log showed `connection authenticated: identity="h34s" method=scram-sha-256` for the bundle's connection: Skunk's SCRAM login works in the production bundle with the `skunk-node-crypto.ts` plugin.
- After eight parallel `GET /api/notes` requests, `pg_stat_activity` held 4 sessions of the user `h34s` (the pool's maximum). After `SIGTERM`, the server exited with status 0, PostgreSQL logged 4 disconnections, and `pg_stat_activity` held 0 sessions of that user.

The kyo-sql code from the database guide ran against the same PostgreSQL; see [Run the kyo-sql tests](database.md#run-the-kyo-sql-tests).
