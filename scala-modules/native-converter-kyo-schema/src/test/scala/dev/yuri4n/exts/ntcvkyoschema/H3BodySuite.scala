package dev.yuri4n.exts.ntcvkyoschema

import dev.yuri4n.exts.kyoh3.*
import dev.yuri4n.exts.kyoh3.KyoTesting.{Call, errors, json}
import dev.yuri4n.exts.ntcvkyoschema.Models.Signup
import kyo.*
import munit.FunSuite

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js

final case class Welcome(message: String) derives Schema, NativeSchema

/** `request.body.json[A]` over a real h3 event, with a schema-backed model. */
class H3BodySuite extends FunSuite:
    given ExecutionContext = ExecutionContext.global

    private val handler: EventHandler =
      KHandler.define(request => request.body.json[Signup].map(signup => Welcome(s"Welcome, ${signup.name}")))

    /** POSTs `body` as JSON and returns the call (status, headers) with the handler's native result. */
    private def post(body: String, development: Boolean = false): Future[(Call, js.Any)] =
      val call = Call(
        method = "POST",
        headers = Seq("content-type" -> "application/json", "content-length" -> body.getBytes("UTF-8").length.toString),
        development = if development then (true: js.Any) else js.undefined,
      )
      val result = call.invoke(handler)
      call.request.send(body)
      call.request.finish()
      result.map(call -> _)

    test("a valid body is 200 with the data envelope") {
      post("{\"name\":\"Ada\",\"email\":\"ada@example.com\",\"age\":36}").map { (call, body) =>
        assertEquals(call.response.status, 200)
        assertEquals(json(body), "{\"data\":{\"message\":\"Welcome, Ada\"}}")
      }
    }

    test("failed schema checks are 422 with the app-authored messages and field paths") {
      post("{\"name\":\"   \",\"email\":\"ada@example.com\",\"age\":17}").map { (call, body) =>
        assertEquals(call.response.status, 422)
        assertEquals(
          json(body),
          "{\"errors\":[{\"message\":\"must not be blank\",\"path\":\"name\"}," +
            "{\"message\":\"must be at least 18\",\"path\":\"age\"}]}",
        )
      }
    }

    test("a body without the schema's shape is 422 with the generic message, also in development") {
      for
        (wrongType, wrongTypeBody) <- post("{\"name\":1,\"email\":\"\",\"age\":36}")
        (fraction, fractionBody) <- post("{\"name\":\"Ada\",\"email\":\"a@b\",\"age\":36.5}", development = true)
      yield
        assertEquals(wrongType.response.status, 422)
        assertEquals(json(wrongTypeBody), errors("Request body does not match the expected shape"))
        assertEquals(fraction.response.status, 422)
        assertEquals(json(fractionBody), errors("Request body does not match the expected shape"))
    }

    test("invalid JSON stays a 400, before the schema runs") {
      post("{\"name\":").map { (call, body) =>
        assertEquals(call.response.status, 400)
        assertEquals(json(body), errors("Request body is not valid JSON"))
      }
    }
end H3BodySuite
