<!-- claude 2026-10-19: created: placeholder notes for the next release. When you release, rename this file to <version>.md with `git mv`, replace every NEXT_VERSION, and fill in the other sections. See docs/releases.md, step 3. -->

h34s `NEXT_VERSION`. This placeholder collects notes for the next release while the work lands. The version is not decided yet.

## Breaking changes

<!-- claude 2026-10-19: created: the strict JSON content type default (issue #41); changed: requests without a body are not affected (review) -->
- **JSON reads require a content type.** `HandlerOptions.requireJsonContentType` now defaults to `true`, so `request.body.json[A]` answers 415 for a request that has a body but no `Content-Type`. A request without a body is not affected. Browsers send some cross-site requests without a content type and without a CORS preflight; the new default keeps them away from JSON reads without any setup. `$fetch` with an object body and `curl --json` already send `application/json`. To keep the old behavior for one handler, pass `HandlerOptions(requireJsonContentType = false)`. `Request.fake` follows the same default and takes the same parameter. See the [migration guide](https://github.com/yuri4n/h34s/blob/vNEXT_VERSION/docs/migration.md#json-reads-require-a-content-type).

## Verified since `0.2.0-alpha.1`

- **PostgreSQL 16.** The ZIO demo's Skunk Notes routes ran in a production build against PostgreSQL 16.15 with `scram-sha-256` authentication. `POST /api/notes` answered 201, invalid input answered 422, and `GET /api/notes` listed the note. Skunk's SCRAM login worked in the bundle. After `SIGTERM`, the server exited with status 0 and no session stayed open. See the [compatibility matrix](https://github.com/yuri4n/h34s/blob/vNEXT_VERSION/docs/compatibility.md#locally-verified-in-a-cloud-session-2026-10-19).
- **kyo-sql.** The kyo-sql code in the [database guide](https://github.com/yuri4n/h34s/blob/vNEXT_VERSION/docs/database.md#run-the-kyo-sql-tests) ran against PostgreSQL 16 in Node: insert and list, a unique violation mapped to a `SafeError`, two rollbacks, and a nested transaction that rolls back only its own part. A new opt-in suite, `KyoSqlPostgresSuite`, runs it when `H34S_POSTGRES_URL` is set. It has not run inside a Nitro bundle.
- **Build memory.** The demo's `nuxt build` needs more than 4 GiB of heap (6144 MiB passed in one run), and its scripts keep `--max-old-space-size=8192`. The cause is now measured. Nitro bundles the linked Scala.js twice in one process: once for the prerendered Nuxt Content route and once for the server, with source maps. See the [development guide](https://github.com/yuri4n/h34s/blob/vNEXT_VERSION/docs/development.md#production-build).

## Changes

- **PostgreSQL in CI.** A new `postgres` job in the Scala.js libraries workflow runs PostgreSQL 16 with SCRAM, the kyo-sql suite, the demo's production build, `smoke.mjs` and a `SIGTERM` session check. It runs on pull requests and pushes to `main` that touch the libraries or the example; it does not run on release tags. Its first run failed on a wrong session count; the [corrected job](https://github.com/yuri4n/h34s/actions/runs/39277276171/job/118144813255) passed on pull request #45.

## Alpha limits

- kyo-sql inside a Nitro bundle and other Nitro presets remain unverified.
- The demo's production build needs more than 4 GiB of Node heap; its scripts pass 8 GiB.
