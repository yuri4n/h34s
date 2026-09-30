package dev.yuri4n.svr.handlers

import dev.yuri4n.exts.zioh3.{ZHandler, defineEventHandler}
import org.getshaka.nativeconverter.NativeConverter.given
import org.getshaka.nativeconverter.{NativeConverter, ParseState}
import zio.stream.ZStream

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.annotation.JSExportTopLevel

given NativeConverter[Unit] with

    extension (a: Unit) override def toNative: scala.scalajs.js.Any = "undefined"

    override def fromNative(ps: ParseState): Unit =
      if ps.json.toJson == "undefined" then () else ps.fail("Expected undefined")

@JSExportTopLevel("default", "test")
val handler = defineEventHandler:
    ZHandler.sendStream(ZStream("a", "b", "c"))
