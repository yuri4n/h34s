// claude 2026-10-20: created: the region that the project tour shows (issue #52)
// #region tour
package dev.yuri4n.svr.app

import dev.yuri4n.exts.zioh3.{EventHandler, HandlerEnv, HandlerOptions, Request, ResponseHead, ZHandler, ZResponseBody, ZShared}
// claude 2026-10-20: created: the greeting service's import (issue #52)
import dev.yuri4n.svr.greetings.Greetings
import dev.yuri4n.svr.notes.{Notes, NotesLive}
import dev.yuri4n.svr.resources.SessionPool
import zio.ZIO

// claude 2026-10-20: changed: the object also supplies `Greetings` (issue #52)
/** The application's one wiring point. It supplies `Notes` and `Greetings` to every handler
  * function; ZHandler supplies the request snapshot, runs ZIO, and writes the response. This object
  * belongs to the example, not to h34s.
  */
object AppHandler:
    /** Every response is `Cache-Control: no-store`; bodies use the default `{ "data": ... }` envelope. */
    private val options = HandlerOptions(defaults = ResponseHead.noStore)

    // claude 2026-10-20: changed: the pool is built when the first note needs a session (issue #52)
    /** One Skunk pool for all requests: built when the first note needs a session, released when Nitro closes. */
    private val database = ZShared.layer(SessionPool.layer)

    // claude 2026-10-20: created: the shared greeting service (issue #52)
    /** The greeting service, built from the startup runtime config on first use. It needs no database. */
    private val greetings = ZShared.layer(Greetings.layer)

    // claude 2026-10-20: created: `Notes` with a lazily built pool (issue #52)
    /** `Notes` takes the pool only when a note operation runs, so a greeting never builds the pool. */
    private val notes = NotesLive.lazily(database.get.map(_.get[SessionPool]))

    // claude 2026-10-20: changed: provides `Greetings` and the lazy `Notes` (issue #52)
    def define[B: ZResponseBody](program: Request => ZIO[HandlerEnv & Notes & Greetings, Throwable, B]): EventHandler =
      ZHandler.define(options): request =>
          greetings.provide[HandlerEnv, Throwable, B](program(request).provideSomeLayer[HandlerEnv & Greetings](notes))
// #endregion tour
