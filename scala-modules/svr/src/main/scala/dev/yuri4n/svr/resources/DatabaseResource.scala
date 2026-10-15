package dev.yuri4n.svr.resources

import cats.syntax.all.*
import cats.effect.std.Console
import ciris.*
import zio.interop.catz.*
import zio.{Task, ZLayer}
import skunk.Session
import org.typelevel.otel4s.trace.Tracer
import zio.ZIO

object DatabaseResource:

    def make(config: DatabaseConfig) =
        given Tracer[Task]  = Tracer.noop
        given Console[Task] = Console.make[Task]

        Session
          .single(
            host = config.host,
            port = config.port,
            user = config.user,
            password = config.password.some,
            database = config.dbName,
          )
          .toScopedZIO
    end make

    def layer =
      ZLayer.scoped(ZIO.serviceWithZIO[DatabaseConfig](make))

    final case class DatabaseConfig(
        host: String,
        port: Int,
        user: String,
        password: String,
        dbName: String,
    )

    object DatabaseConfig:
        def config = (
          env("MY_APP_DB_HOST").as[String],
          env("MY_APP_DB_PORT").as[Int],
          env("MY_APP_DB_USER").as[String],
          env("MY_APP_DB_PASSWORD").as[String],
          env("MY_APP_DB_NAME").as[String],
        ).parMapN(DatabaseConfig.apply)

        def layer = ZLayer.fromZIO(config.load[Task])
    end DatabaseConfig
end DatabaseResource
