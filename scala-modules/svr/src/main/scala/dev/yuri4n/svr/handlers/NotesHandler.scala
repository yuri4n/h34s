package dev.yuri4n.svr.handlers

import dev.yuri4n.exts.zioh3.ZHandler
import dev.yuri4n.exts.zioh3schema.readBodyWithSchema
import dev.yuri4n.svr.app.AppHandler
import dev.yuri4n.svr.notes.{CreateNote, Notes}
import zio.ZIO

import scala.scalajs.js.annotation.JSExportTopLevel

@JSExportTopLevel("default", "notes-get")
val listNotes = AppHandler.define:
    ZIO.serviceWithZIO[Notes](_.list)

@JSExportTopLevel("default", "notes-post")
val createNote = AppHandler.define:
    for
      input <- ZHandler.readBodyWithSchema[CreateNote]
      note <- ZIO.serviceWithZIO[Notes](_.create(input.text))
      _ <- ZHandler.setResponseStatus(201)
    yield note
