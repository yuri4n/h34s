package dev.yuri4n.svr.notes

import dev.yuri4n.exts.ntcvzioschema.NativeSchema
import dev.yuri4n.svr.resources.SessionPool
import skunk.Session
import skunk.codec.all.*
import skunk.implicits.*
import zio.{Scope, Task, ZIO, ZLayer}
import zio.schema.{DeriveSchema, Schema, StandardType, derived}
import zio.schema.validation.Validation

/** One derivation per model: the ZIO Schema, and the native converter derived from it. */
final case class Note(id: Int, text: String) derives Schema, NativeSchema

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
