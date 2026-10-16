package dev.yuri4n.svr.notes

import org.getshaka.nativeconverter.NativeConverter
import skunk.Session
import skunk.codec.all.*
import skunk.implicits.*
import zio.{Task, ZLayer}
import zio.schema.{DeriveSchema, Schema, StandardType}
import zio.schema.validation.Validation

final case class Note(id: Int, text: String) derives NativeConverter
final case class CreateNote(text: String)

object CreateNote:
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
    val layer: ZLayer[Session[Task], Nothing, Notes] = ZLayer.fromFunction(new NotesLive(_))
