package dev.yuri4n.server

import dev.yuri4n.server.ext.h3.{AnyEvHandlerRes, CommonEvHandler, H3Event1, mkHandler, given}
import typings.h3.mod.{EventHandlerObject, EventHandlerRequest, defineEventHandler, getRouterParam}
import zio.ZIO
import zio.json.JsonCodec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.annotation.*

final case class UsersIdResponse(
    source: String,
    method: String,
    path: String,
    id: String,
) derives JsonCodec

private val usersIdHandlerFn: CommonEvHandler =
  mkHandler((event: H3Event1) =>
      val id = getRouterParam(event, "id").getOrElse("")

      if id == "67" then ZIO.fail(new Exception("No 67 allowed"))
      else
          ZIO.succeed(
            UsersIdResponse(
              source = "scala-js",
              method = event.method.toString(),
              path = event.path.toString(),
              id = id,
            ),
          )
      end if
      //
      ,
  )

@JSExportTopLevel("usersIdHandler")
val usersIdHandler: CommonEvHandler =
  defineEventHandler[EventHandlerRequest, AnyEvHandlerRes](
    EventHandlerObject(usersIdHandlerFn),
  )
