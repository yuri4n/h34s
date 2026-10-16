package dev.yuri4n.svr.notes

import dev.yuri4n.exts.ntcvzioschema.NativeSchema
import zio.schema.Schema
import zio.test.*

import scala.scalajs.js

object CreateNoteSpec extends ZIOSpecDefault:
    private val codec = NativeSchema(Schema[CreateNote])

    def spec = suite("CreateNote model schema")(
      test("normalizes text and accepts the domain boundaries") {
        assertTrue(codec.decode(js.Dynamic.literal(text = " hello ")) == Right(CreateNote("hello")),
          codec.decode(js.Dynamic.literal(text = "a")) == Right(CreateNote("a")),
          codec.decode(js.Dynamic.literal(text = "a" * 2000)) == Right(CreateNote("a" * 2000)))
      },
      test("rejects invalid structure and domain values before endpoint code receives a model") {
        val invalid: List[js.Any] = List(null, js.Array(), true, js.Dynamic.literal(),
          js.Dynamic.literal(text = null), js.Dynamic.literal(text = 42), js.Dynamic.literal(text = js.Dynamic.literal()),
          js.Dynamic.literal(text = " "), js.Dynamic.literal(text = "a" * 2001))
        assertTrue(invalid.forall(codec.decode(_).isLeft))
      },
    )
