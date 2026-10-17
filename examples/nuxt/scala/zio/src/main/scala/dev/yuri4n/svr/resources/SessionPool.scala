package dev.yuri4n.svr.resources

import cats.effect.Resource
import cats.effect.std.Console
import dev.yuri4n.exts.zioh3.ZRuntimeConfig
import dev.yuri4n.svr.app.{AppConfig, DatabaseConfig}
import fs2.io.net.Network
import org.typelevel.otel4s.trace.Tracer
import skunk.Session
import zio.interop.catz.*
import zio.{Scope, Task, ZIO, ZLayer}

/** Skunk sessions from one application-scoped pool. Each request takes its own session. */
final class SessionPool(sessions: Resource[Task, Session[Task]]):
    /** A pooled session that returns to the pool when `Scope` closes (the request scope in a handler). */
    def session: ZIO[Scope, Throwable, Session[Task]] = sessions.toScopedZIO

object SessionPool:
    /** Reads the startup runtime config and creates the pool. Connections open on first use. */
    val layer: ZLayer[Any, Throwable, SessionPool] =
      ZLayer.scoped(
        for
          config   <- ZRuntimeConfig.load[AppConfig]
          sessions <- pooled(config.database).toScopedZIO
        yield SessionPool(sessions),
      )

    private def pooled(config: DatabaseConfig): Resource[Task, Resource[Task, Session[Task]]] =
      given Tracer[Task]  = Tracer.noop
      given Console[Task] = Console.make[Task]
      given Network[Task] = Network.forAsync[Task]
      val password = Some(config.password.reveal).filter(_.nonEmpty)
      Session.Builder[Task]
        .withHost(config.host)
        .withPort(config.port)
        .withCredentials(Session.Credentials(config.user, password))
        .withDatabase(config.name)
        .pooled(max = 4)
