package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.h3.CommonEvHandler
import org.getshaka.nativeconverter.{NativeConverter, ParseState}
import zio.*
import zio.test.*

import scala.compiletime.testing.typeCheckErrors
import scala.concurrent.ExecutionContext
import scala.scalajs.js

/** Tests the rule for the value a handler finishes with, as `mkHandler` applies it.
  *
  * Created by Claude Code on 2026-10-13. A handler ends with a `Responded` proof from a toolbox
  * operation, or with a body value that has a `NativeConverter`; `Unit` is a compile error. These
  * tests call the handler with a fake h3 event. They check the `undefined` result for a proof with
  * no converter in scope, with the library converter from `ntcvzio` in scope, and with a competing
  * `NativeConverter[Responded]`; the body path; the inference for an always-failing handler; the
  * compile-time rejection of `Unit`, with and without `ntcvzio.given`; and the two runtime guards
  * in `deliver`. `mkHandler` links the `h3` facade's import, so the zio-h3 test build links
  * CommonJS modules; see `build.sbt`. `SendStreamBoundarySpec` covers the same rule through a real
  * h3 server.
  */
object HandlerResultSpec extends ZIOSpecDefault:

    given ExecutionContext = ExecutionContext.global

    /** The proof, as a toolbox operation returns it. This spec lives inside the toolbox package. */
    private val responded: UIO[Responded] = ZIO.succeed(Responded.value)

    def spec = suite("mkHandler result rule")(
      test("a Responded result resolves to undefined with no converter in scope") {
        for result <- invoke(mkHandler(h = responded), fakeEvent(handled = true))
        yield assertTrue(js.isUndefined(result))
      },
      test("a Responded result resolves to undefined with ntcvzio.given in scope") {
        import dev.yuri4n.exts.ntcvzio.given
        for result <- invoke(mkHandler(h = responded), fakeEvent(handled = true))
        yield assertTrue(
          js.isUndefined(result),
          js.isUndefined(summon[NativeConverter[Unit]].toNative(())),
        )
      },
      test("a NativeConverter[Responded] in scope does not change the result") {
        given NativeConverter[Responded] with
            extension (value: Responded) def toNative: js.Any = "WRONG"

            override def fromNative(parseState: ParseState): Responded = Responded.value

        for result <- invoke(mkHandler(h = responded), fakeEvent(handled = true))
        yield assertTrue(js.isUndefined(result))
      },
      test("a body value converts through its NativeConverter") {
        for result <- invoke(mkHandler(h = ZIO.succeed("body")))
        yield assertTrue(result.asInstanceOf[String] == "body")
      },
      test("an always-failing handler needs no type argument and keeps its status and messages") {
        val event = fakeEvent()
        for result <- invoke(mkHandler(h = ZHandler.failWith(404, "missing")), event)
        yield assertTrue(
          event.node.res.statusCode.asInstanceOf[Int] == 404,
          js.JSON.stringify(result) == """{"errors":["missing"]}""",
        )
      },
      test("a handler that finishes with Unit does not compile") {
        val errors = typeCheckErrors("mkHandler(h = ZIO.unit)")
        assertTrue(errors.nonEmpty, errors.exists(_.message.contains("finishes with Unit")))
      },
      test("a NativeConverter[Unit] in scope does not make a Unit handler compile") {
        import dev.yuri4n.exts.ntcvzio.given
        val errors = typeCheckErrors("mkHandler(h = ZIO.unit)")
        assertTrue(
          errors.nonEmpty,
          errors.exists(_.message.contains("finishes with Unit")),
          js.isUndefined(summon[NativeConverter[Unit]].toNative(())),
        )
      },
      test("a body returned after the response was sent is dropped") {
        for result <- invoke(mkHandler(h = ZIO.succeed("late")), fakeEvent(handled = true))
        yield assertTrue(js.isUndefined(result))
      },
      test("a proof without a response becomes a 500 error body, not a 404") {
        val event = fakeEvent()
        for result <- invoke(mkHandler(h = responded), event)
        yield assertTrue(
          event.node.res.statusCode.asInstanceOf[Int] == 500,
          js.JSON.stringify(result) ==
            """{"errors":["The handler finished without sending a response."]}""",
        )
      },
    ) @@ TestAspect.timeout(10.seconds)

    /** A minimal h3 event. `deliver` and the error boundary read `handled`, `setResponseStatus`
      * writes to `node.res`, and the tests read nothing else.
      */
    private def fakeEvent(handled: Boolean = false): js.Dynamic =
      js.Dynamic.literal(handled = handled, node = js.Dynamic.literal(res = js.Dynamic.literal()))

    /** Calls the h3 handler with `event` and returns the value its promise resolves to. */
    private def invoke(handler: CommonEvHandler, event: js.Dynamic = fakeEvent()): Task[js.Any] =
      ZIO.fromPromiseJS(handler.asInstanceOf[js.Function1[js.Any, js.Promise[js.Any]]](event))
end HandlerResultSpec
