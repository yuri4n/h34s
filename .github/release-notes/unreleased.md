<!-- claude 2026-10-19: created: placeholder notes for the next release. When you release, rename this file to <version>.md with `git mv`, replace every NEXT_VERSION, and fill in the other sections. See docs/releases.md, step 3. -->

h34s `NEXT_VERSION`. This placeholder collects notes for the next release while the work lands. The version is not decided yet.

## Verified since `0.2.0-alpha.1`

- **PostgreSQL 16.** The ZIO demo's Skunk Notes routes ran in a production build against PostgreSQL 16.15 with `scram-sha-256` authentication. `POST /api/notes` answered 201, invalid input answered 422, and `GET /api/notes` listed the note. Skunk's SCRAM login worked in the bundle. After `SIGTERM`, the server exited with status 0 and no session stayed open. See the [compatibility matrix](https://github.com/yuri4n/h34s/blob/vNEXT_VERSION/docs/compatibility.md#locally-verified-in-a-cloud-session-2026-10-19).
- **kyo-sql.** The kyo-sql code in the [database guide](https://github.com/yuri4n/h34s/blob/vNEXT_VERSION/docs/database.md#run-the-kyo-sql-tests) ran against PostgreSQL 16 in Node: insert and list, a unique violation mapped to a `SafeError`, and two rollbacks. A new opt-in suite, `KyoSqlPostgresSuite`, runs it when `H34S_POSTGRES_URL` is set. It has not run inside a Nitro bundle.
- **Build memory.** The demo's `nuxt build` still needs `--max-old-space-size=8192`, and the cause is now measured. Nitro bundles the linked Scala.js twice in one process: once for the prerendered Nuxt Content route and once for the server, with source maps. See the [development guide](https://github.com/yuri4n/h34s/blob/vNEXT_VERSION/docs/development.md#production-build).

## Alpha limits

- kyo-sql inside a Nitro bundle and other Nitro presets remain unverified.
- The demo's production build needs an 8 GiB Node heap.
