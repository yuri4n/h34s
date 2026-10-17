package dev.yuri4n.svr.app

import dev.yuri4n.exts.zioh3.{EventHandler, HandlerEnv, HandlerOptions, Request, ResponseHead, ZHandler, ZResponseBody, ZShared}
import dev.yuri4n.svr.notes.{Notes, NotesLive}
import dev.yuri4n.svr.resources.SessionPool
import zio.ZIO

/** The application's one wiring point. It supplies `Notes` to every handler function; ZHandler
  * supplies the request snapshot, runs ZIO, and writes the response. This object belongs to the
  * example, not to h34s.
  */
object AppHandler:
    /** Every response is `Cache-Control: no-store`; bodies use the default `{ "data": ... }` envelope. */
    private val options = HandlerOptions(defaults = ResponseHead.noStore)

    /** One Skunk pool for all requests: built on first use, released when Nitro closes. */
    private val database = ZShared.layer(SessionPool.layer)

    def define[B: ZResponseBody](program: Request => ZIO[HandlerEnv & Notes, Throwable, B]): EventHandler =
      ZHandler.define(options): request =>
          database.provide(program(request).provideSomeLayer[HandlerEnv & SessionPool](NotesLive.layer))
