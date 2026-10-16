package dev.yuri4n.svr.app

import dev.yuri4n.svr.notes.{Notes, NotesLive}
import dev.yuri4n.svr.resources.DatabaseResource
import dev.yuri4n.svr.resources.DatabaseResource.DatabaseConfig
import zio.ZLayer

object Services:
    // Each request owns its connection. A shared pool is a separate application-lifecycle feature.
    val layer: ZLayer[Any, Throwable, Notes] =
      DatabaseConfig.layer >>> DatabaseResource.layer >>> NotesLive.layer
