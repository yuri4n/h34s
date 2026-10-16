# Develop the Nuxt/Scala.js example

This repository example uses the `0.1.0-alpha.1` libraries. Node runs the Scala.js handlers inside Nitro; Java and sbt are build-time tools. The application itself is excluded from library publication. See [releases.md](releases.md) for the published private Maven coordinates.

The example consumes the local `@yuri4n/h34s-nuxt` package for build orchestration and Nuxt development integration. External applications can install its [published `0.1.0-alpha.1` package](https://github.com/yuri4n/h34s/releases/tag/npm-v0.1.0-alpha.1). The [npm tooling guide](npm-tooling.md) explains how to configure the same integration without copying scripts.

## Prerequisites

Use Node 24, pnpm 10.34.4, Java 25 for the combined Kyo/ZIO example (Java 21 remains sufficient for ZIO-only tasks), sbt 1.11.7, and Docker with Compose for the optional database example. The current build selects Scala.js 1.22.0, Scala 3.5.2 for ZIO modules, and Scala 3.9.0 for Kyo 1.0.0-RC7. `project/build.properties` pins sbt; `.jvmopts` supplies the stack and heap sizes needed by the linked dependencies. The recovered Nuxt dependency set uses pnpm 10's build allowlist.

In the prepared cloud environment, first run:

```sh
cd /workspace/h34s
source /workspace/tooling/activate-kyo.sh
```

The activation script puts the prepared `sbt` launcher on `PATH`, so the npm CLI can invoke it. `/workspace/tooling/h34s-sbt` is also available for direct Scala commands. It keeps caches writable and facade lockfile updates outside the checkout. On a normal development machine, use the installed `sbt` launcher.

## 1. Start from this repository

Clone the private repository using your existing GitHub access, then run:

```sh
pnpm install --frozen-lockfile
pnpm run scala:fast
pnpm dev
```

In the prepared cloud environment the linking command is:

```sh
/workspace/tooling/h34s-sbt 'nuxtsvr/fastLinkJS' 'kyoExample/fastLinkJS'
```

Development loads the linked Scala.js ES modules directly in Node. Nitro watches both output directories and replaces its worker after relinking, keeping Node’s module cache fresh. This avoids the large Rollup graphs that exhausted an 8 GB heap during bundled watch rebuilds. The current `pnpm dev` script enables Node source maps automatically and needs no heap override. The application pins `h3` as a direct runtime dependency for the linked modules.

Make a direct HTTP request to `/api/test`; the response should be `abc`. This route needs no database. Scala source changes need another `nuxtsvr/fastLinkJS`; Nuxt watches the linked JavaScript. For continuous Scala linking, use `pnpm run scala:watch` in another terminal. The `h34s-scala` CLI reads both modules from `h34s.config.mjs`.

Nuxt development mode also enables detailed Scala error responses and maps Node stack traces back to `.scala` files. See the [debugging guide](debugging.md) for error details, stack traces, and attaching a Node debugger.

The cloud instance uses an ignored `.nuxtrc` to select Node's native SQLite connector for Nuxt Content and disable remote font providers. Those settings address this environment's SQLite addon startup and network limitations. They do not affect the Scala database layer.

The independent [Kyo example](kyo.md) adds GET and POST `/api/kyo/greeting`. Its compiled output lives in `server/.scala-kyo`; link both applications before starting this combined Nuxt demonstration. Kyo-only applications depend on `kyoH3Ext`, without the ZIO example or its database integration.

## 2. Run the model and database example

Set `MY_APP_DB_HOST`, `MY_APP_DB_PORT`, `MY_APP_DB_USER`, `MY_APP_DB_PASSWORD`, and `MY_APP_DB_NAME` in your process or an ignored local environment file. For local Compose, use host `127.0.0.1` and port `5432` (or a free port of your choice). Supply your own local password; do not commit it.

```sh
docker compose up -d postgres
docker compose exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Compose mounts the example schema into PostgreSQL's initialization directory. PostgreSQL runs those scripts only when initializing an empty volume. For an existing development database, apply the additive example schema explicitly:

```sh
docker compose exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  < scala-modules/svr/src/main/resources/sql/01-init.sql
```

Pass the same `MY_APP_DB_*` variables to the Nuxt process, rebuild Scala.js, then start Nuxt. Exercise the endpoints with:

```sh
curl --fail -H 'Content-Type: application/json' \
  --data '{"text":"My first note"}' http://127.0.0.1:3000/api/notes
curl --fail http://127.0.0.1:3000/api/notes
```

POST returns status 201 with `{ "id": 1, "text": "My first note" }` for a new empty database. GET lists notes by ID. Blank text returns a safe 422 response. The ID may differ when the database already contains rows.

`notes/Notes.scala` defines the application model, request DTO, service, and Skunk implementation. The `Note` response derives `NativeConverter`. The `CreateNote` input opts into ZIO Schema: its schema trims and validates text, and the handler calls `readBodyWithSchema` without repeating those rules. Invalid input returns HTTP 422 with `Request body does not match schema`. The plain NativeConverter-only reader remains available without schema dependencies; see [schema.md](schema.md).

Skunk's typed SQL interpolator encodes input values as bound parameters; it never concatenates them into SQL. `Services.scala` composes the database layer, and `AppHandler.scala` supplies it to endpoint programs. Database schema initialization is a setup action, never a per-request side effect.

The application includes `server/plugins/skunk-node-crypto.ts`: the pinned Skunk version calls Node's `require('crypto')` for SCRAM authentication, so the plugin installs Node's loader before Nitro accepts requests. Keep this plugin when copying the database example into an ES-module application. PostgreSQL's default SCRAM authentication stays enabled. Restart Nuxt if authentication ran before the plugin was installed; the failed library initializer cannot recover in the same process.

## Production build

With the combined build toolchain selected, run:

```sh
pnpm build
```

This runs `pnpm run scala:full` before Nuxt's production build. The packaged `h34s-scala build` step reads `h34s.config.mjs`, clears previous output from both `server/.scala` and `server/.scala-kyo`, runs `nuxtsvr/fullLinkJS` and `kyoExample/fullLinkJS`, and checks that both outputs exist. A failed link stops the build before Nuxt can package stale development code. Use `pnpm run scala:full` to run that step on its own.

This build entry point does not establish complete application packaging or a tested Nix deployment. The [release checks](releases.md#validate-and-publish-a-release) validate reusable library artifacts separately from packaging the example application.

## Checks

```sh
sbt 'nativeConverterZIOSchemaExt/test' 'zioH3SchemaExt/test' 'zioH3Ext/test' 'kyoH3Ext/test' 'nuxtsvr/test' 'nuxtsvr/fastLinkJS' 'kyoExample/fastLinkJS'
node --test scripts/scala-source-maps.test.mjs scripts/scala-dev-modules.test.mjs
node scripts/smoke.mjs
node scripts/kyo-smoke.mjs
```

The smoke check needs the running example and database. Wait for Nitro's build to complete first. It verifies streaming, a real insert/read with schema-normalized text, and safe responses for blank, null, missing, and incorrectly typed input. It creates one note in the selected development database. `H34S_BASE_URL` can select a different local port.

Earlier verification in the prepared cloud checkout covered 17 Kyo tests, all 31 ZIO handler/stream tests, 13 development-tool checks, application linking, Nuxt startup, the HTTP smoke check against PostgreSQL 16 with SCRAM, and the home page returning HTTP 200 in the earlier bundled profile. The direct-module profile also passed both smoke scripts, including PostgreSQL insert/read, streaming, and rejected input; a Scala service edit/relink updated its live Kyo response without restarting Nuxt. A query-failure probe in Nuxt development mode returned status 500 with the original cause and a stack frame mapped to `Notes.scala`. Production response suppression and Nitro's production mode replacement were checked separately. PostgreSQL showed no remaining application connections after successful requests, input failures, or query failure. The ZIO socket-close unit tests used real h3 with mocked Node responses; that evidence did not include an actual ZIO client-abort integration check. Kyo has a separate real client-abort test; its evidence is recorded in the [Kyo guide](kyo.md).

The optional schema slice passed 15 native codec tests, 7 real h3 request/error tests, 2 tests of the actual `CreateNote` schema, the existing 31 handler/stream tests, and application development linking under the earlier Scala.js 1.20.1 profile. At that checkpoint its updated HTTP smoke script had not been rerun against Nuxt/PostgreSQL; those automated tests do not establish that end-to-end result. The [schema guide](schema.md#dependencies-and-compatibility) separates that historical profile from the current Scala.js 1.22.0 configuration. Check the matching release workflow for validation of the release revision.

Tests cover native conversion, error boundaries, application provisioning, request cleanup, stream backpressure and cleanup, and h3 response-close behavior. Use direct HTTP for stream responses; in-process Nitro local-fetch streaming is not supported by this scoped adapter yet.

Scala-to-TypeScript generation, complete application packaging, Nix support, and application-scoped database pooling remain pending. `scala:types` runs ScalablyTyped (TypeScript to Scala), not Scala-to-TypeScript generation.
