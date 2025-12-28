package dev.yuri4n.server

import dev.yuri4n.server.ext.h3.{AnyEvHandlerRes, CommonEvHandler, H3Event1, ZIOH3, mkHandler}
import typings.h3.mod.{EventHandlerObject, EventHandlerRequest, EventHandler_, defineEventHandler}
import zio.json.JsonCodec
import zio.json.ast.Json

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.scalajs.js.annotation.*

final case class Response(
    source: String,
    method: String,
    path: String,
    body: Map[String, Json],
) derives JsonCodec

private val scalaHandlerFn: CommonEvHandler =
  mkHandler((event: H3Event1) =>
    ZIOH3
      .readBody[Map[String, Json]](event)
      .tap(zio.Console.print(_))
      .map(body =>
        Response(
          source = "scala-js",
          method = event.method.toString(),
          path = event.path.toString(),
          body = body,
        ),
      ),
  )

// Expose an h3 handler compiled from Scala.js and consumed inside the Nuxt server.
@JSExportTopLevel("scalaHandler")
val handler: CommonEvHandler =
  defineEventHandler[EventHandlerRequest, AnyEvHandlerRes](
    EventHandlerObject(scalaHandlerFn),
  )
