package dev.yuri4n.svr.notes

import dev.yuri4n.exts.ntcvzioschema.NativeSchema
import dev.yuri4n.svr.resources.SessionPool
import skunk.codec.all.*
import skunk.implicits.*
import skunk.{Query, Session}
import zio.schema.validation.Validation
import zio.schema.{DeriveSchema, Schema, StandardType, derived}
import zio.{Scope, Task, ZEnvironment, ZIO, ZLayer}

/** A note in the application: what the service creates and lists. It has no wire or table format. */
final case class Note(id: Int, text: String)

/** The JSON that the Notes routes send. One derivation: the ZIO Schema and the native converter. */
final case class NoteView(id: Int, text: String) derives Schema, NativeSchema

object NoteView:
    def from(note: Note): NoteView = NoteView(note.id, note.text)

/** The JSON that `POST /api/notes` reads. */
final case class CreateNote(text: String) derives NativeSchema

object CreateNote:
    /** Trims the text, then requires 1-2000 characters. The native converter uses this schema. */
    given Schema[CreateNote] =
      given Schema[String] = Schema.Primitive(StandardType.StringType)
        .transform(_.trim, identity)
        .validation(Validation.minLength(1) && Validation.maxLength(2000))
      DeriveSchema.gen[CreateNote]

trait Notes:
    def create(text: String): Task[Note]
    def list: Task[List[Note]]

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

    /** Notes over at most one pooled session per request. The session is taken on first use, so a
      * handler that rejects its input never touches the pool, and it returns to the pool when the
      * request scope closes.
      */
    val layer: ZLayer[SessionPool & Scope, Nothing, Notes] =
      ZLayer.fromZIO(
        for
          pool    <- ZIO.service[SessionPool]
          scope   <- ZIO.service[Scope]
          session <- pool.session.provideEnvironment(ZEnvironment(scope)).memoize
        yield NotesLive(session),
      )
