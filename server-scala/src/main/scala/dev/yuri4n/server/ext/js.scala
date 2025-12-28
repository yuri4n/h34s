package dev.yuri4n.server.ext

import zio.json.{JsonDecoder, JsonEncoder}
import zio.{Task, ZIO}

import scala.scalajs.js as js1

object js:

    extension (any: js1.UndefOr[js1.Any])
      def as[A](using JsonDecoder[A]): Task[A] =
        any match
            case _: Unit => ZIO.fail(new Exception("trying to parse undefined js value"))
            case _       =>
              ZIO
                .attempt(js1.JSON.stringify(any))
                .flatMap(jsonStr =>
                  ZIO
                    .fromEither(JsonDecoder[A].decodeJson(jsonStr))
                    .mapError(err => new Exception(s"Failed to decode body: $err")),
                )

    extension [A](a: A)
      def asJS(using JsonEncoder[A]): js1.UndefOr[js1.Any] =
        a match
            case _: Unit => js1.undefined
            case _       =>
              js1.JSON.parse(JsonEncoder[A].encodeJson(a).toString())
end js
