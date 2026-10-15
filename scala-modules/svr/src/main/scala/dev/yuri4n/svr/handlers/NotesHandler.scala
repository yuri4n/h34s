package dev.yuri4n.svr.handlers

import dev.yuri4n.exts.zioh3.{SafeError, ZHandler}
import dev.yuri4n.svr.app.AppHandler
import dev.yuri4n.svr.notes.{CreateNote, Notes}
import zio.ZIO

import scala.scalajs.js.annotation.JSExportTopLevel

private final case class InvalidNote(message: String) extends SafeError:
    override def status = 422

@JSExportTopLevel("default", "notes-get")
val listNotes = AppHandler.define:
    ZIO.serviceWithZIO[Notes](_.list)

@JSExportTopLevel("default", "notes-post")
val createNote = AppHandler.define:
    for
      input <- ZHandler.readBody[CreateNote].mapError(_ => InvalidNote("Expected a JSON object with a text field"))
      // NativeConverter 0.9 accepts null/missing string fields. Validate before using Scala String methods.
      value <- ZIO.fromOption(Option(input.text))
        .orElseFail(InvalidNote("Expected a JSON object with a text field"))
        .map(_.trim)
      _ <- ZIO.fail(InvalidNote("Text must contain between 1 and 2000 characters")).when(value.isEmpty || value.length > 2000)
      note <- ZIO.serviceWithZIO[Notes](_.create(value))
      _ <- ZHandler.setResponseStatus(201)
    yield note
