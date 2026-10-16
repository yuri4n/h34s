package dev.yuri4n.svr.app

import dev.yuri4n.exts.h3.CommonEvHandler
import dev.yuri4n.exts.ntcvzio.given
import dev.yuri4n.exts.zioh3.{SH3Event, ZHandler}
import dev.yuri4n.svr.notes.Notes
import org.getshaka.nativeconverter.NativeConverter
import zio.{Scope, ZIO}

/** Application policy and services belong here, outside endpoint business logic. */
object AppHandler:
    def define[A: NativeConverter](
        program: ZIO[SH3Event & Scope & Notes, Throwable, A],
    ): CommonEvHandler =
      ZHandler.define(program.provideSomeLayer[SH3Event & Scope](Services.layer))

    def define(
        program: ZIO[SH3Event & Scope & Notes, Throwable, Unit],
    ): CommonEvHandler = define[Unit](program)
