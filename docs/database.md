# Use PostgreSQL

h34s has no database module, on purpose. Good PostgreSQL clients already run inside Scala.js on Node: use kyo-sql in a Kyo application and Skunk in a ZIO application, directly. h34s gives you the two pieces that connect them to handlers:

- an application-scoped pool: `Shared` (Kyo) or `ZShared` (ZIO), built on first use and released when Nitro closes;
- a request scope for one session or transaction per request.

Your models, SQL, migrations and error mapping stay in your application. See [lifetimes](lifetimes.md) and [configuration](configuration.md) for the h34s side.

## The pattern

1. Declare the connection settings in `nuxt.config.ts` and read them with `RuntimeConfig.load` (Kyo) or `ZRuntimeConfig.load` (ZIO). Keep the password in a `Secret`, supplied at runtime.
2. Open one pool per application inside `Shared` or `ZShared`.
3. In each request, run your statements on that pool: one transaction (kyo-sql) or one pooled session (Skunk), released with the request scope.
4. Map database errors that the client may see, such as a unique-constraint violation, to your own `SafeError` inside the service. Let every other database error stay a defect: an opaque 500.

Create the schema with your own migration tool or scripts. Handlers should never create or change tables.

## Kyo: kyo-sql and kyo-sql-postgres

<!-- claude 2026-10-19: changed: status after the PostgreSQL 16 run (issue #29) -->
**Status: runs against PostgreSQL 16 in Node; not run inside Nitro.** The service code below (`NotesLive`, `Note`, `NoteView`, `DuplicateNote`) is copied without changes into the Kyo example's opt-in test, `KyoSqlPostgresSuite`. On 2026-10-19, with kyo-sql-postgres 1.0.0-RC7, it inserted and listed a note, mapped a unique violation to `DuplicateNote`, and rolled back two failed transactions against PostgreSQL 16.15 with SCRAM authentication. See [Run the kyo-sql tests](#run-the-kyo-sql-tests). The `Database` and `AppHandler` snippets compile, but they were not run, because they need Nitro. The demo's Nuxt application does not use kyo-sql. Treat this code as a starting point.

Add the client to your application:

```scala
libraryDependencies += "io.getkyo" %%% "kyo-sql-postgres" % "1.0.0-RC7"
```

It brings kyo-sql, kyo-net and kyo-schema-json with it.

### Configure the URL

kyo-sql takes one URL such as `postgres://user:password@host:5432/db`. Declare it as a private runtime config key, empty by default, and set `NUXT_DATABASE_URL` at runtime:

```scala
import dev.yuri4n.exts.ntcvkyoschema.{NativeSchema, secretSchema}

final case class DatabaseConfig(url: Secret, verifyTls: Boolean) derives Schema, NativeSchema
final case class AppConfig(database: DatabaseConfig) derives Schema, NativeSchema
```

`secretSchema` is the bridge's `Schema[Secret]`: import it where you derive a schema that holds a secret.

kyo-sql 1.0.0-RC7 parses this URL itself. Read these rules before you copy a URL from a hosting provider (they come from the kyo-sql source and are not tested by h34s):

- **The port is required.** `postgres://u:p@host/db` fails with `SqlConnectionUrlParseException`. Add `:5432` when your provider leaves it out.
- **The database name is required.**
- **Credentials are not percent-decoded.** A password written as `p%40ss` is sent as those exact characters. Use a password that needs no encoding, or decode it before you build the URL.
- **`sslmode=require` encrypts but does not verify.** `allow`, `prefer` and `require` accept any certificate and host name. `verify-ca` and `verify-full` need `sslrootcert`, a file path. To verify against the system certificate store without a file, set the TLS settings in code, as below. Settings in code win over the URL's trust choice; an `sslmode` in the URL still wins over the mode in code.

### Open one pool for the application

```scala
import dev.yuri4n.exts.kyoh3.*
import kyo.*
import kyo.net.NetTlsConfig

object Database:
    /** One pool for all requests: opened on first use, closed when Nitro closes. */
    val client: Shared[PostgresClient] = Shared(
      RuntimeConfig.load[AppConfig].map { config =>
        val sqlConfig =
          if config.database.verifyTls then
              SqlConfig.default.tlsMode(SqlConfig.TlsMode.Require).tls(NetTlsConfig.default)
          else SqlConfig.default
        PostgresClient.init(config.database.url.reveal, sqlConfig)
      },
    )
```

`PostgresClient.init` registers the pool in the `Scope` it runs in. Inside `Shared`, that is the resource's own scope, so the pool closes when Nitro closes. Do not call `DB.run(url)` per request: it opens and closes a whole pool on every call.

Set `verifyTls` to `false` only for a local database without TLS.

### Create the table

Create the table with your migration tool or setup script, not in a handler. The code below expects this table:

<!-- claude 2026-10-19: created: the table that the kyo-sql code uses -->
```sql
CREATE TABLE IF NOT EXISTS notes (
  id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  text text NOT NULL UNIQUE
);
```

`UNIQUE` gives the `23505` violation that `create` maps to `DuplicateNote`.

### Run statements per request

Keep SQL in your service and map the errors a client may see. Keep three models apart: the table row (read by kyo-sql), the domain note (what the service returns), and the JSON that a route sends. A new column then reaches the JSON only when you add it to the wire model.

```scala
/** The domain note. It has no table or wire format. */
final case class Note(id: Int, text: String)

/** The JSON that the Notes routes send. */
final case class NoteView(id: Int, text: String) derives Schema, NativeSchema

object NoteView:
    def from(note: Note): NoteView = NoteView(note.id, note.text)

final case class DuplicateNote() extends SafeError:
    def message: String      = "A note with this text already exists"
    override def status: Int = 409

final class NotesLive(client: PostgresClient):
    import NotesLive.NoteRow

    def create(text: String): Note < (Async & Abort[DuplicateNote | SqlException]) =
      Abort.recover[SqlServerConstraintViolationException](error =>
        if error.sqlState == "23505" then Abort.fail(DuplicateNote()) else Abort.fail(error),
      )(DB.run(client)(DB.transaction(
        sql"INSERT INTO notes (text) VALUES ($text) RETURNING id, text".as[NoteRow].run.map(_.head.toNote),
      )))

    def list: Chunk[Note] < (Async & Abort[SqlException]) =
      DB.run(client)(sql"SELECT id, text FROM notes ORDER BY id".as[NoteRow].run.map(_.map(_.toNote)))

object NotesLive:
    /** The table row. It stays inside the service. */
    private final case class NoteRow(id: Int, text: String) derives Schema:
        def toNote: Note = Note(id, text)
```

- `sql"..."` binds `$text` as a parameter. The value is never concatenated into the SQL text.
<!-- claude 2026-10-19: changed: the recovered-statement case, as observed against PostgreSQL 16 -->
- `DB.transaction` commits when the body succeeds and rolls back when it fails, also on a non-SQL `Abort`. A body that recovers from a failed statement still rolls back: the transaction then fails with `SqlRequestTransactionFailedStatementException`. To keep the rest of the work, put the step that may fail in a nested `DB.transaction`. A client disconnect interrupts it too, so the transaction can roll back. When the insert must finish anyway, run it with `KHandler.uninterruptible(notes.create(text))`; see [lifetimes](lifetimes.md#when-the-client-disconnects).
- `SQLSTATE 23505` is a unique violation. Map only the states your client should see; `SqlException` details can contain values from the database, so never put them in a `SafeError` message.

Provide the service in your `AppHandler`, the same way as any Kyo service:

```scala
object AppHandler:
    def define[B: ResponseBody](program: Request => B < (HandlerEffects & Env[NotesLive]))(using Frame): EventHandler =
      KHandler.define(HandlerOptions(defaults = ResponseHead.noStore)): request =>
          Database.client.get.map(client => Env.run(NotesLive(client))(program(request)))
```

### Run kyo-sql inside Nitro

kyo-net picks an I/O backend when it starts. It prefers native backends loaded through FFI and falls back to Node's `net` and `tls`. In a Nitro server, force the Node backend:

```sh
KYO_NET_BACKEND=node node .output/server/index.mjs
```

<!-- claude 2026-10-19: changed: what the Node test run showed about backend selection -->
**Unverified inside Nitro.** h34s has not run kyo-sql inside a Nitro bundle. In the Node test run, without `KYO_NET_BACKEND`, kyo-net selected its `node` backend by itself and logged a warning that the FFI backends were unavailable, because the `koffi` npm package was not installed. The FFI backends, the bundle size, and non-Node Nitro presets (which have no `net` or `tls`) are open questions. Test your deployment target before you rely on it.

### Run the kyo-sql tests

`KyoSqlPostgresSuite` in `examples/nuxt/scala/kyo/src/test/scala/` runs the service code above against a real database. It runs only when `H34S_POSTGRES_URL` holds a kyo-sql URL. Without the variable, munit reports its four tests as skipped.

1. Start PostgreSQL 16 and create a role and a database for the test. The suite creates the `notes` table if it does not exist and adds rows with unique text.
2. Run the suite from the repository root:

   ```sh
   H34S_POSTGRES_URL="postgres://h34s:$(cat ~/.h34s-db-password)@127.0.0.1:5432/h34s" sbt -batch kyoExample/test
   ```

Expected result: `Passed: Total 4, Failed 0, Errors 0, Passed 4`. The suite runs in Node through sbt, not inside Nitro. Set `H34S_POSTGRES_REQUIRED=true` to make the tests fail instead of skip when the URL is missing; the `postgres` CI job does this. <!-- claude 2026-10-19: created: the required flag -->

## ZIO: Skunk in a `ZShared` layer

<!-- claude 2026-10-19: changed: the Notes flow ran against PostgreSQL 16 (issue #29) -->
The demo's Notes example uses Skunk 1.0.0-M12 through zio-interop-cats. Its handlers are unit-tested. On 2026-10-19, a production build of the demo ran against PostgreSQL 16.15 with `scram-sha-256` authentication: `POST /api/notes` answered 201, invalid input answered 422, `GET /api/notes` listed the note, and after `SIGTERM` no session of the example's user stayed in `pg_stat_activity`. See [compatibility](compatibility.md) for the commands and the output.

### Configure

The settings come from `runtimeConfig.database` (see [configuration](configuration.md)): `NUXT_DATABASE_HOST`, `NUXT_DATABASE_PORT`, `NUXT_DATABASE_USER`, `NUXT_DATABASE_PASSWORD` and `NUXT_DATABASE_NAME`. An empty password means none.

### Build the pool once

`SessionPool.scala` opens one Skunk pool from the startup config:

```scala
/** Skunk sessions from one application-scoped pool. Each request takes its own session. */
final class SessionPool(sessions: Resource[Task, Session[Task]]):
    /** A pooled session that returns to the pool when `Scope` closes (the request scope in a handler). */
    def session: ZIO[Scope, Throwable, Session[Task]] = sessions.toScopedZIO

object SessionPool:
    /** Reads the startup runtime config and creates the pool. Connections open on first use. */
    val layer: ZLayer[Any, Throwable, SessionPool] =
      ZLayer.scoped(
        for
          config   <- ZRuntimeConfig.load[AppConfig]
          sessions <- pooled(config.database).toScopedZIO
        yield SessionPool(sessions),
      )

    private def pooled(config: DatabaseConfig): Resource[Task, Resource[Task, Session[Task]]] =
      given Tracer[Task]  = Tracer.noop
      given Console[Task] = Console.make[Task]
      given Network[Task] = Network.forAsync[Task]
      val password = Some(config.password.reveal).filter(_.nonEmpty)
      Session.Builder[Task]
        .withHost(config.host)
        .withPort(config.port)
        .withCredentials(Session.Credentials(config.user, password))
        .withDatabase(config.name)
        .pooled(max = 4)
```

`AppHandler` wraps the layer in `ZShared`, so all requests share one pool: `ZShared.layer(SessionPool.layer)`. See [ZIO handlers](zio.md#compose-services-in-your-apphandler).

### Take one session per request

`NotesLive.layer` takes at most one session per request, in the request scope, on first use. The session returns to the pool when the request ends, and a request that fails validation never takes one: invalid input is a 422 even when the database is down. A private `NoteRow` is the table row; the service returns the domain `Note`, and the handlers send `NoteView`:

```scala
/** Notes in PostgreSQL. `session` gives the request's session; the first call takes it. */
final class NotesLive(session: Task[Session[Task]]) extends Notes:
    import NotesLive.{insert, selectAll}

    def create(value: String): Task[Note] = session.flatMap(_.unique(insert)(value)).map(_.toNote)

    def list: Task[List[Note]] = session.flatMap(_.execute(selectAll)).map(_.map(_.toNote))

object NotesLive:
    /** The table row. It stays inside this interpreter: a new column does not reach the JSON. */
    private final case class NoteRow(id: Int, text: String):
        def toNote: Note = Note(id, text)

    private val row = (int4 *: text).to[NoteRow]

    private val insert: Query[String, NoteRow] = sql"INSERT INTO h34s_notes (text) VALUES ($text) RETURNING id, text".query(row)

    private val selectAll: Query[skunk.Void, NoteRow] = sql"SELECT id, text FROM h34s_notes ORDER BY id".query(row)

    val layer: ZLayer[SessionPool & Scope, Nothing, Notes] =
      ZLayer.fromZIO(
        for
          pool    <- ZIO.service[SessionPool]
          scope   <- ZIO.service[Scope]
          session <- pool.session.provideEnvironment(ZEnvironment(scope)).memoize
        yield NotesLive(session),
      )
```

`NotesHandlerSpec` checks the order with a pool whose database is down: invalid input takes no session; valid input takes one.

Skunk's `$text` is a parameter codec; the value is bound separately. For a transaction, open Skunk's `session.transaction` resource (`Resource[Task, Transaction[Task]]`) in the same request scope, for example with `.toScopedZIO`. To map a unique violation to your own `SafeError`, match `case SqlState.UniqueViolation(_) =>` on the failure inside the service (`skunk.SqlState`, SQLSTATE 23505). These two recipes come from the Skunk API; the demo does not use them.

### Keep the Skunk Node plugin

Skunk 1.0.0-M12 calls `require('crypto')` for SCRAM authentication, even in ES-module output. The demo installs Node's loader in a Nitro plugin, `examples/nuxt/server/plugins/skunk-node-crypto.ts`. <!-- claude 2026-10-19: changed: SCRAM evidence --> With that plugin, the production bundle logged in to PostgreSQL 16 with `scram-sha-256`; the server log showed `connection authenticated: identity="h34s" method=scram-sha-256`. Copy that plugin when you use Skunk in your own Nuxt app. If authentication ran before the plugin was installed, restart the server.

## Set up a local database

<!-- claude 2026-10-19: changed: link to the cloud-session setup without Docker -->
The demo's PostgreSQL setup, initialization script and smoke test are in the [development guide](development.md). To use an installed PostgreSQL without Docker, as in a Claude Code cloud session, follow [Start PostgreSQL and Redis](maintainers/cloud.md#start-postgresql-and-redis). The demo's table is created by `examples/nuxt/scala/zio/src/main/resources/sql/01-init.sql`, which PostgreSQL runs only for an empty volume.

## Test without a database

Keep the service behind a trait, and test handlers with an in-memory implementation. The demo's `NotesHandlerSpec` runs the ZIO handlers with a fake request and an in-memory `Notes`, and `NotesLive` with a pool whose database is down; see [testing](testing.md). Test the SQL itself against a real PostgreSQL in your own integration tests.
