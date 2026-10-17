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

**Status: sketch, not verified.** The code below was checked line by line against the kyo-sql 1.0.0-RC7 sources. It has not been compiled, run or tested by h34s, and nothing in this repository uses kyo-sql yet. Treat it as a starting point.

Add the client to your application:

```scala
libraryDependencies += "io.getkyo" %%% "kyo-sql-postgres" % "1.0.0-RC7"
```

It brings kyo-sql, kyo-net and kyo-schema-json with it.

### Configure the URL

kyo-sql takes one URL such as `postgres://user:password@host:5432/db`. Declare it as a private runtime config key, empty by default, and set `NUXT_DATABASE_URL` at runtime:

```scala
final case class DatabaseConfig(url: Secret, verifyTls: Boolean) derives NativeConverter
final case class AppConfig(database: DatabaseConfig) derives NativeConverter
```

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

### Run statements per request

Keep SQL in your service and map the errors a client may see:

```scala
final case class Note(id: Int, text: String) derives Schema, NativeSchema

final case class DuplicateNote() extends SafeError:
    def message: String      = "A note with this text already exists"
    override def status: Int = 409

final class NotesLive(client: PostgresClient):
    def create(text: String): Note < (Async & Abort[DuplicateNote | SqlException]) =
      Abort.recover[SqlServerConstraintViolationException](error =>
        if error.sqlState == "23505" then Abort.fail(DuplicateNote()) else Abort.fail(error),
      )(DB.run(client)(DB.transaction(
        sql"INSERT INTO notes (text) VALUES ($text) RETURNING id, text".as[Note].run.map(_.head),
      )))

    def list: Chunk[Note] < (Async & Abort[SqlException]) =
      DB.run(client)(sql"SELECT id, text FROM notes ORDER BY id".as[Note].run)
```

- `sql"..."` binds `$text` as a parameter. The value is never concatenated into the SQL text.
- `DB.transaction` commits when the body succeeds and rolls back when it fails, also on a non-SQL `Abort`. A body that recovers from a failed statement still rolls back.
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

**Unverified.** kyo-sql has not been run inside a Nitro bundle by h34s. The FFI backends, the bundle size, and non-Node Nitro presets (which have no `net` or `tls`) are open questions. Test your deployment target before you rely on it.

## ZIO: Skunk in a `ZShared` layer

The demo's Notes example uses Skunk 1.0.0-M12 through zio-interop-cats. This code is compiled and its handlers are unit-tested. A production build of the demo loaded the configuration, built the pool on the first request and released it on `SIGTERM`, but no PostgreSQL server was available: the insert and read flow has not been run on this branch (see [compatibility](compatibility.md)).

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

`NotesLive.layer` takes a session in the request scope, and the session returns to the pool when the request ends:

```scala
final class NotesLive(session: Session[Task]) extends Notes:
    private val codec = (int4 *: text).to[Note]

    def create(value: String): Task[Note] =
      session.unique(sql"INSERT INTO h34s_notes (text) VALUES ($text) RETURNING id, text".query(codec))(value)

    def list: Task[List[Note]] =
      session.execute(sql"SELECT id, text FROM h34s_notes ORDER BY id".query(codec))

object NotesLive:
    /** Notes over one pooled session that lives as long as the request scope. */
    val layer: ZLayer[SessionPool & Scope, Throwable, Notes] =
      ZLayer.fromZIO(ZIO.serviceWithZIO[SessionPool](_.session).map(NotesLive(_)))
```

The demo's `AppHandler` builds `NotesLive.layer` before the handler runs, so every Notes request takes a session before it reads the body. When the database is down, even invalid input gets a 500 instead of a 422 (seen in a production build of the demo without PostgreSQL). If you want input errors first, read and validate the body before you take a session, for example by providing the layer inside the handler after `request.body.json`.

Skunk's `$text` is a parameter codec; the value is bound separately. For a transaction, open Skunk's `session.transaction` resource (`Resource[Task, Transaction[Task]]`) in the same request scope, for example with `.toScopedZIO`. To map a unique violation to your own `SafeError`, match `case SqlState.UniqueViolation(_) =>` on the failure inside the service (`skunk.SqlState`, SQLSTATE 23505). These two recipes come from the Skunk API; the demo does not use them.

### Keep the Skunk Node plugin

Skunk 1.0.0-M12 calls `require('crypto')` for SCRAM authentication, even in ES-module output. The demo installs Node's loader in a Nitro plugin, `examples/nuxt/server/plugins/skunk-node-crypto.ts`. Copy that plugin when you use Skunk in your own Nuxt app. If authentication ran before the plugin was installed, restart the server.

## Set up a local database

The demo's PostgreSQL setup, initialization script and smoke test are in the [development guide](development.md). The demo's table is created by `examples/nuxt/scala/zio/src/main/resources/sql/01-init.sql`, which PostgreSQL runs only for an empty volume.

## Test without a database

Keep the service behind a trait, and test handlers with an in-memory implementation. The demo's `NotesHandlerSpec` runs the ZIO handlers with a fake request and an in-memory `Notes`; see [testing](testing.md). Test the SQL itself against a real PostgreSQL in your own integration tests.
