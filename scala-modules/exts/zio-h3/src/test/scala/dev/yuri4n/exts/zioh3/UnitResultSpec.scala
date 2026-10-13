package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.h3.CommonEvHandler
import org.getshaka.nativeconverter.{NativeConverter, ParseState}
import zio.*
import zio.test.*

import scala.concurrent.ExecutionContext
import scala.scalajs.js

/** Tests the value `mkHandler` hands back to h3 when the handler finishes with `Unit`.
  *
  * Created by Claude Code on 2026-10-13. h3 treats every result other than `undefined` as a
  * response body, so a handler that already wrote the response, for example with `sendStream`,
  * must resolve to `undefined`. The toolbox owns that conversion through `ResultConverter`. These
  * tests call the handler with a fake h3 event and check the result with no converter in scope,
  * with the library converter from `ntcvzio` in scope, and with a competing converter that returns
  * a string. The string case is the one that raised a hidden `ERR_HTTP_HEADERS_SENT` in the example
  * handler. `mkHandler` links the `h3` facade's import, so the zio-h3 test build links CommonJS
  * modules; see `build.sbt`.
  */
object UnitResultSpec extends ZIOSpecDefault:

    given ExecutionContext = ExecutionContext.global

    def spec = suite("mkHandler result conversion")(
      test("a Unit result resolves to undefined with no NativeConverter[Unit] in scope") {
        for result <- invoke(mkHandler(h = ZIO.unit))
        yield assertTrue(js.isUndefined(result))
      },
      test("a Unit result resolves to undefined with ntcvzio.given in scope") {
        import dev.yuri4n.exts.ntcvzio.given
        for result <- invoke(mkHandler(h = ZIO.unit))
        yield assertTrue(
          js.isUndefined(result),
          js.isUndefined(summon[NativeConverter[Unit]].toNative(())),
        )
      },
      test("a Unit result resolves to undefined when a NativeConverter[Unit] in scope returns a string") {
        given NativeConverter[Unit] with
            extension (value: Unit) def toNative: js.Any = "undefined"

            override def fromNative(parseState: ParseState): Unit = ()

        for result <- invoke(mkHandler(h = ZIO.unit))
        yield assertTrue(
          js.isUndefined(result),
          summon[NativeConverter[Unit]].toNative(()).asInstanceOf[String] == "undefined",
        )
      },
      test("a non-Unit result still converts through its NativeConverter") {
        for result <- invoke(mkHandler(h = ZIO.succeed("body")))
        yield assertTrue(result.asInstanceOf[String] == "body")
      },
      test("an ErrorResponse from a Unit handler still produces an error body, not undefined") {
        // Only the success value goes through `ResultConverter`; the error boundary builds its own
        // body. This test checks that the `Unit` instance does not reach that body and that the
        // status and messages survive. (Changed by Claude Code on 2026-10-13: asserts the status and
        // body now that the error boundary matches `ErrorResponse` first.)
        val event = fakeEvent()
        for result <- invoke(mkHandler[Unit](h = ZHandler.failWith(404, "missing")), event)
        yield assertTrue(
          event.node.res.statusCode.asInstanceOf[Int] == 404,
          js.JSON.stringify(result) == """{"errors":["missing"]}""",
        )
      },
    ) @@ TestAspect.timeout(10.seconds)

    /** A minimal h3 event. The error boundary reads `handled`, `setResponseStatus` writes to
      * `node.res`, and the tests read nothing else.
      */
    private def fakeEvent(): js.Dynamic =
      js.Dynamic.literal(handled = false, node = js.Dynamic.literal(res = js.Dynamic.literal()))

    /** Calls the h3 handler with `event` and returns the value its promise resolves to. */
    private def invoke(handler: CommonEvHandler, event: js.Dynamic = fakeEvent()): Task[js.Any] =
      ZIO.fromPromiseJS(
        handler.asInstanceOf[js.Function1[js.Any, js.Promise[js.Any]]](event),
      )
end UnitResultSpec
