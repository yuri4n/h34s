package dev.yuri4n.kyoexample.postgres

import dev.yuri4n.exts.kyoh3.*
import dev.yuri4n.exts.ntcvkyoschema.NativeSchema
import kyo.*
import munit.FunSuite

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js

// claude 2026-10-19: created: the kyo-sql code of docs/database.md ("Run statements per request"),
// copied without changes, so that the suite below runs the documented code against PostgreSQL.

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

/** Runs the kyo-sql code of docs/database.md against a real PostgreSQL. Opt-in: the tests run only
  * when `H34S_POSTGRES_URL` holds a kyo-sql URL, for example
  * `postgres://h34s:<password>@127.0.0.1:5432/h34s`. Without it, munit reports them as skipped,
  * unless `H34S_POSTGRES_REQUIRED=true`: then they fail, so that CI cannot pass by skipping them.
  * The suite creates the guide's `notes` table if it does not exist. Every row it writes has text that
  * starts with `h34s kyo-sql `, and each test deletes those rows when it ends. The table stays.
  */
// claude 2026-10-19: created: opt-in integration suite for the kyo-sql guide code (issue #29)
class KyoSqlPostgresSuite extends FunSuite:
    given ExecutionContext = ExecutionContext.global

    private def environment(name: String): Option[String] =
      js.Dynamic.global.process.env.selectDynamic(name).asInstanceOf[js.UndefOr[String]].toOption.filter(_.nonEmpty)

    private val url: Option[String] = environment("H34S_POSTGRES_URL")

    // claude 2026-10-19: created: CI sets this so that a missing URL fails instead of skipping
    private val required: Boolean = environment("H34S_POSTGRES_REQUIRED").contains("true")

    /** The guide's table. The guide shows the same statement. */
    private val createTable =
      "CREATE TABLE IF NOT EXISTS notes (id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY, text text NOT NULL UNIQUE)"

    // claude 2026-10-19: created: removes the suite's rows, so that runs do not accumulate data
    private val cleanup = "DELETE FROM notes WHERE text LIKE 'h34s kyo-sql %'"

    private final case class Boom() extends Exception("boom")

    private final case class Count(count: Long) derives Schema

    private def unique(label: String): String = s"h34s kyo-sql $label ${js.Math.random()}"

    private def count(client: PostgresClient, text: String): Long < (Async & Abort[SqlException]) =
      DB.run(client)(sql"SELECT count(*) AS count FROM notes WHERE text = $text".as[Count].run.map(_.head.count))

    /** Opens one pool for the test, creates the table, runs `body`, deletes the suite's rows, and closes
      * the pool. `Scope.run` runs finalizers in reverse order, so the cleanup runs before the pool closes.
      */
    // claude 2026-10-19: created: registers the cleanup after the pool opens
    private def withClient[A](body: PostgresClient => A < (Async & Abort[Throwable]))(using Frame): Future[A] =
      import AllowUnsafe.embrace.danger
      val program = Scope.run(
        PostgresClient.init(url.get).map(client =>
          DB.run(client)(DB.executeRaw(createTable))
            .andThen(Scope.ensure(DB.run(client)(DB.executeRaw(cleanup))))
            .andThen(body(client)),
        ),
      )
      Fiber.Unsafe.init(Abort.run[Throwable](program)).toFuture().map(_.getOrThrow)

    private def pgTest(name: String)(body: PostgresClient => Unit < (Async & Abort[Throwable]))(using munit.Location): Unit =
      test(name) {
        if required && url.isEmpty then fail("H34S_POSTGRES_REQUIRED=true, but H34S_POSTGRES_URL is not set")
        assume(url.isDefined, "set H34S_POSTGRES_URL to run the PostgreSQL integration tests")
        withClient(body)
      }

    pgTest("create inserts a note and list returns it") { client =>
      val notes = NotesLive(client)
      val text  = unique("create")
      for
        note   <- notes.create(text)
        listed <- notes.list
      yield
        assertEquals(note.text, text)
        assert(listed.contains(note), s"list did not contain $note")
        assertEquals(NoteView.from(note), NoteView(note.id, text))
    }

    pgTest("a unique violation becomes DuplicateNote") { client =>
      val notes = NotesLive(client)
      val text  = unique("duplicate")
      for
        _      <- notes.create(text)
        second <- Abort.run[DuplicateNote | SqlException](notes.create(text))
        rows   <- count(client, text)
      yield
        assertEquals(second, Result.fail(DuplicateNote()))
        assertEquals(rows, 1L)
    }

    pgTest("a transaction that fails with a non-SQL Abort rolls back") { client =>
      val text = unique("rollback")
      val failing: Unit < (Async & Abort[Boom | SqlException]) =
        DB.run(client)(DB.transaction(
          sql"INSERT INTO notes (text) VALUES ($text)".execute.andThen(Abort.fail(Boom())),
        ))
      for
        result <- Abort.run[Boom | SqlException](failing)
        rows   <- count(client, text)
      yield
        assertEquals(result, Result.fail(Boom()))
        assertEquals(rows, 0L)
    }

    pgTest("a transaction whose body recovers from a failed statement still rolls back") { client =>
      val existing = unique("existing")
      val text     = unique("recovered")
      val recovered: Unit < (Async & Abort[SqlException]) =
        DB.run(client)(DB.transaction(
          for
            _ <- sql"INSERT INTO notes (text) VALUES ($text)".execute
            _ <- Abort.recover[SqlException](_ => ())(sql"INSERT INTO notes (text) VALUES ($existing)".execute.unit)
          yield (),
        ))
      for
        _      <- NotesLive(client).create(existing)
        result <- Abort.run[SqlException](recovered)
        rows   <- count(client, text)
      yield
        // kyo-sql 1.0.0-RC7 rolls back and fails the transaction with this exception.
        assert(
          result.failure.exists(_.isInstanceOf[SqlRequestTransactionFailedStatementException]),
          s"expected SqlRequestTransactionFailedStatementException, got $result",
        )
        assertEquals(rows, 0L)
    }

    // claude 2026-10-19: created: the guide's advice to keep the rest of the work with a nested transaction
    pgTest("a nested transaction that fails rolls back only its own part") { client =>
      val existing = unique("nested-existing")
      val text     = unique("nested-outer")
      // The outer transaction returns the nested one's result, so the test can check that it failed.
      val outer: Result[SqlException, Unit] < (Async & Abort[SqlException]) =
        DB.run(client)(DB.transaction(
          for
            _     <- sql"INSERT INTO notes (text) VALUES ($text)".execute
            inner <- Abort.run[SqlException](
              DB.transaction(sql"INSERT INTO notes (text) VALUES ($existing)".execute.unit),
            )
          yield inner,
        ))
      for
        _      <- NotesLive(client).create(existing)
        result <- Abort.run[SqlException](outer)
        rows   <- count(client, text)
      yield
        assert(result.isSuccess, s"expected the outer transaction to commit, got $result")
        val inner = result.getOrThrow
        assert(
          inner.failure.exists {
            case error: SqlServerConstraintViolationException => error.sqlState == "23505"
            case _                                            => false
          },
          s"expected the nested transaction to fail with a unique violation, got $inner",
        )
        assertEquals(rows, 1L)
    }
end KyoSqlPostgresSuite
