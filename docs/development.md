# Develop the Nuxt/Scala.js example

This is an unpublished development example. Node runs the Scala.js handlers inside Nitro; Java and sbt are build-time tools.

## Prerequisites

Use Node 24, pnpm 10.34.4, Java 21, sbt 1.11.7, and Docker with Compose for the optional database example. `project/build.properties` pins sbt; `.jvmopts` supplies the stack and heap sizes needed by the linked dependencies. The recovered Nuxt dependency set uses pnpm 10's build allowlist.

In the prepared cloud environment, first run:

```sh
cd /workspace/h34s
source /workspace/tooling/activate.sh
```

The cloud sbt launcher is a Java launcher, so use `/workspace/tooling/h34s-sbt` for the commands below, without sbt shell-script flags such as `-batch`. It keeps caches writable and facade lockfile updates outside the checkout. On a normal development machine, use the installed `sbt` launcher.

## 1. Start from this repository

Clone the private repository using your existing GitHub access, then run:

```sh
pnpm install --frozen-lockfile
sbt 'nuxtsvr/fastLinkJS'
NODE_OPTIONS=--max-old-space-size=8192 pnpm dev
```

In the prepared cloud environment the linking command is:

```sh
/workspace/tooling/h34s-sbt 'nuxtsvr/fastLinkJS'
```

The unoptimized Scala.js example currently needs an 8 GB Node heap for Nitro bundling (the default 4 GB heap ran out of memory). Allow enough machine memory for Node and the 4 GB sbt heap, or finish linking before starting Nuxt.

Make a direct HTTP request to `/api/test`; the response should be `abc`. This route needs no database. Scala source changes need another `nuxtsvr/fastLinkJS`; Nuxt watches the linked JavaScript. For continuous Scala linking, use `sbt '~nuxtsvr/fastLinkJS'` in another terminal.

Nuxt development mode also enables detailed Scala error responses and composes source maps back to `.scala` files. See the [debugging guide](debugging.md) for error details, stack traces, and attaching a Node debugger.

The cloud instance uses an ignored `.nuxtrc` to select Node's native SQLite connector for Nuxt Content and disable remote font providers. Those settings address this environment's SQLite addon startup and network limitations. They do not affect the Scala database layer.

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

`notes/Notes.scala` defines the application model, request DTO, service, and Skunk implementation. DTOs derive `NativeConverter`, so their native JavaScript representation matches their fields. Skunk's typed SQL interpolator encodes input values as bound parameters; it never concatenates them into SQL. `Services.scala` composes the database layer, and `AppHandler.scala` supplies it to endpoint programs. Schema initialization is a setup action, never a per-request side effect.

The application includes `server/plugins/skunk-node-crypto.ts`: the pinned Skunk version calls Node's `require('crypto')` for SCRAM authentication, so the plugin installs Node's loader before Nitro accepts requests. Keep this plugin when copying the database example into an ES-module application. PostgreSQL's default SCRAM authentication stays enabled. Restart Nuxt if authentication ran before the plugin was installed; the failed library initializer cannot recover in the same process.

## Checks

```sh
sbt 'zioH3Ext/test' 'nuxtsvr/fastLinkJS'
node --test scripts/scala-source-maps.test.mjs
node scripts/smoke.mjs
```

The smoke check needs the running example and database. Wait for Nitro's build to complete first. It verifies streaming, a real insert/read, and safe responses for blank, null, missing, and incorrectly typed input. It creates one note in the selected development database. `H34S_BASE_URL` can select a different local port.

Verified in the prepared cloud checkout: all 31 Scala handler/stream tests, six source-map checks, application linking, Nuxt startup, the HTTP smoke check against PostgreSQL 16 with SCRAM, and the home page returning HTTP 200. A query-failure probe in Nuxt development mode returned status 500 with the original cause and a stack frame mapped to `Notes.scala`. Production response suppression and Nitro's production mode replacement were checked separately. PostgreSQL showed no remaining application connections after successful requests, input failures, or query failure. Socket-close unit tests use real h3 with mocked Node responses; an actual client-abort integration check remains to be added.

Tests cover native conversion, error boundaries, application provisioning, request cleanup, stream backpressure and cleanup, and h3 response-close behavior. Use direct HTTP for stream responses; in-process Nitro local-fetch streaming is not supported by this scoped adapter yet.

The historical production script, scala-ts generation, Nix packaging, and application-scoped database pooling are not validated by this development slice. `scala:types` runs ScalablyTyped (TypeScript to Scala), not Scala-to-TypeScript generation.
