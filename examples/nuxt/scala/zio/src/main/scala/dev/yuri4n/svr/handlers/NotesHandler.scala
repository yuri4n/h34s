package dev.yuri4n.svr.handlers

import dev.yuri4n.exts.zioh3.*
import dev.yuri4n.svr.app.AppHandler
import dev.yuri4n.svr.notes.{CreateNote, NoteView, Notes}
import zio.ZIO

import scala.scalajs.js.annotation.JSExportTopLevel

/** The handler functions, testable with `Request.fake` and a fake `Notes`. They map domain notes to
  * the wire model `NoteView`.
  */
object NotesHandler:
    val list: Request => ZIO[Notes, Throwable, List[NoteView]] =
      _ => ZIO.serviceWithZIO[Notes](_.list).map(_.map(NoteView.from))

    /** The schema validates and trims the input before any database work; the handler adds no
      * validation code.
      */
    val create: Request => ZIO[HandlerEnv & Notes, Throwable, NoteView] = request =>
      for
        input <- request.body.json[CreateNote]
        note  <- ZIO.serviceWithZIO[Notes](_.create(input.text))
        _     <- Response.status(Status.Created)
      yield NoteView.from(note)

@JSExportTopLevel("default", "notes-get")
val listNotes: EventHandler = AppHandler.define(NotesHandler.list).at(Route.get("/api/notes"))

@JSExportTopLevel("default", "notes-post")
val createNote: EventHandler = AppHandler.define(NotesHandler.create).at(Route.post("/api/notes"))
