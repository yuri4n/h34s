package dev.yuri4n.server.ext.h3

import dev.yuri4n.server.ext.js.*
import typings.h3.anon.H3EventEventHandlerReques
import typings.h3.mod.{
  EventHandlerRequest,
  EventHandlerResponse,
  EventHandler_,
  H3Event,
  readBody as h3ReadBody,
  setResponseStatus as h3SetResponseStatus,
}
import zio.json.{JsonDecoder, JsonEncoder}
import zio.{Task, Unsafe, ZIO}

import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*

type H3Event1        = H3Event[EventHandlerRequest]
type AnyEvHandlerRes = EventHandlerResponse[Any]
type CommonEvHandler = EventHandler_[EventHandlerRequest, AnyEvHandlerRes]

val runtime = zio.Runtime.default

given Conversion[H3Event1, H3EventEventHandlerReques] =
  _.asInstanceOf[H3EventEventHandlerReques]

given Conversion[H3EventEventHandlerReques, H3Event1] =
  _.asInstanceOf[H3Event1]

final case class ErrorResponse(errors: Seq[String]) derives JsonEncoder

def mkHandler[A: JsonEncoder](
    h: H3Event1 => Task[A],
)(using ec: ExecutionContext): CommonEvHandler =
  js.Any
    .fromFunction1 { implicit event: H3Event1 =>
      Unsafe.unsafe { implicit unsafe =>
        runtime.unsafe
          .runToFuture(
            h(event)
              .map(_.asJS)
              .catchAll: t =>
                  ZIOH3.failWith(500, t.getMessage()),
          )
          .toJSPromise
      }
    }
    .asInstanceOf[CommonEvHandler]

object ZIOH3:

    def readBody[T: JsonDecoder](event: H3Event1)(using ec: ExecutionContext): Task[T] =
      ZIO
        .fromFuture(implicit ec => h3ReadBody[T, H3Event1, js.Any](event).toFuture)
        .flatMap(_.as[T])

    def setResponseStatus(status: Int)(using event: H3Event1): Task[Unit] =
      ZIO.attempt(h3SetResponseStatus(event, status))

    def failWith(status: Int, msgs: String*)(using event: H3Event1): Task[js.Any] =
      setResponseStatus(status) *> ZIO.attempt(ErrorResponse(msgs).asJS)
end ZIOH3
