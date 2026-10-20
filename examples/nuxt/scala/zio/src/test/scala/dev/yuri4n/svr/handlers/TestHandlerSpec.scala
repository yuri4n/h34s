package dev.yuri4n.svr.handlers

import dev.yuri4n.exts.zioh3.*
import zio.test.*

// claude 2026-10-20: created: the quickstart route through ZHandler.test (ADR-0006, Q10)
/** The quickstart's handler function runs without h3, Nitro or a server; its stream is collected. */
object TestHandlerSpec extends ZIOSpecDefault:
    def spec = suite("TestHandler")(
      test("the quickstart route sends its letters as a stream of text") {
        for response <- ZHandler.test(TestHandler.letters)(Request.fake())
        yield assertTrue(response.body == TestResponse.Body.Streamed(List("a", "b", "c")))
      },
    )
