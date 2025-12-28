package dev.yuri4n.server

import dev.yuri4n.server.ext.h3.{AnyEvHandlerRes, CommonEvHandler, H3Event1, ZIOH3, mkHandler}
import typings.h3.mod.{EventHandlerObject, EventHandlerRequest, EventHandler_, defineEventHandler}
import zio.json.JsonCodec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.scalajs.js.annotation.*

final case class SomethingComplex(
    fieldA: String,
    fieldB: Int,
    fieldC: Boolean,
) derives JsonCodec


final case class AnotherHandlerBody(
    someComplexField: SomethingComplex,
) derives JsonCodec

final case class AnotherHandlerResponse(
    source: String,
    method: String,
    path: String,
    body: AnotherHandlerBody,
) derives JsonCodec

private val scalaHandlerFn: CommonEvHandler =
  mkHandler((event: H3Event1) =>
    ZIOH3
      .readBody[AnotherHandlerBody](event)
      .tap(b => zio.Console.printLine(b.toString()))
      .map(body =>
        AnotherHandlerResponse(
          source = "scala-js",
          method = event.method.toString(),
          path = event.path.toString(),
          body = body,
        ),
      ),
  )

// Expose an h3 handler compiled from Scala.js and consumed inside the Nuxt server.
@JSExportTopLevel("anotherHandler")
val anotherHandler: CommonEvHandler =
  defineEventHandler[EventHandlerRequest, AnyEvHandlerRes](
    EventHandlerObject(scalaHandlerFn),
  )
