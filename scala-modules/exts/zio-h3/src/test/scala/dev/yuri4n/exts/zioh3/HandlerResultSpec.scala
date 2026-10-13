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
  * `NativeConverter[Responded]`; the body path, including bare `None` and `Some` values; the
  * inference for an always-failing handler; the example application's program; the compile-time
  * rejection of `Unit`, with and without `ntcvzio.given`; the runtime guards in `deliver`,
  * including the warning they log; and the no-op of `sendStream` and `sendNoContent` after the
  * response was sent, read when the effect runs. Four tests pin the error body for a `SafeError`
  * failure, a defect with and without a message, and an interruption (Changed by Claude Code on
  * 2026-10-13: this sentence and those tests). `mkHandler` links the `h3` facade's import, so the
  * zio-h3 test build links CommonJS modules; see `build.sbt`. `SendStreamBoundarySpec` covers the
  * same rule through a real h3 server.
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
      test("a bare None is null, a bare Some(a) is a, and an Option[A] behaves the same") {
        for
            none     <- invoke(mkHandler(h = ZIO.succeed(None)))
            some     <- invoke(mkHandler(h = ZIO.succeed(Some("a"))))
            optNone  <- invoke(mkHandler(h = ZIO.succeed(Option.empty[String])))
            optSome  <- invoke(mkHandler(h = ZIO.succeed(Option("a"))))
        yield assertTrue(
          none == null,
          some.asInstanceOf[String] == "a",
          optNone == null,
          optSome.asInstanceOf[String] == "a",
        )
      },
      test("the example handler program fetches, then ends with no content") {
        import dev.yuri4n.exts.ntcvzio.given
        val event = respondingFakeEvent()
        for result <- invoke(mkHandler(h = ZHandler.`$fetch`[Unit]("hi") *> ZHandler.sendNoContent), event)
        yield assertTrue(js.isUndefined(result), event.node.res.statusCode.asInstanceOf[Int] == 204)
      },
      test("sendNoContent reads the response state when it runs, not when it is built") {
        val event = respondingFakeEvent()
        val program =
          for
              ev  <- ZHandler.getEvent
              end  = ev.sendNoContent
              _   <- ev.sendNoContent(205)
              r   <- end
          yield r
        for result <- invoke(mkHandler(h = program), event)
        yield assertTrue(js.isUndefined(result), event.node.res.statusCode.asInstanceOf[Int] == 205)
      },
      test("an always-failing handler needs no type argument and keeps its status and messages") {
        val event = fakeEvent()
        for result <- invoke(mkHandler(h = ZHandler.failWith(404, "missing")), event)
        yield assertTrue(
          event.node.res.statusCode.asInstanceOf[Int] == 404,
          js.JSON.stringify(result) == """{"errors":["missing"]}""",
        )
      },
      // Created by Claude Code on 2026-10-13: the next four tests pin the error body text.
      test("a SafeError failure answers 500 with its message, not null") {
        val event = fakeEvent()
        for result <- invoke(mkHandler(h = ZHandler.getRouterParam("id")), event)
        yield assertTrue(
          event.node.res.statusCode.asInstanceOf[Int] == 500,
          js.JSON.stringify(result) == """{"errors":["Missing router parameter: id"]}""",
        )
      },
      test("a defect with a message reports it and leaves the status unchanged") {
        val event = fakeEvent()
        for result <- invoke(mkHandler(h = ZIO.die(new RuntimeException("boom"))), event)
        yield assertTrue(
          js.isUndefined(event.node.res.statusCode),
          js.JSON.stringify(result) == """{"errors":["boom"]}""",
        )
      },
      test("a defect without a message reports its class name, not null") {
        val event = fakeEvent()
        for result <- invoke(mkHandler(h = ZIO.die(new IllegalStateException())), event)
        yield assertTrue(
          js.isUndefined(event.node.res.statusCode),
          js.JSON.stringify(result) == """{"errors":["java.lang.IllegalStateException"]}""",
        )
      },
      test("an interrupted handler reports the interruption and leaves the status unchanged") {
        val event = fakeEvent()
        for result <- invoke(mkHandler(h = ZIO.interrupt), event)
        yield assertTrue(
          js.isUndefined(event.node.res.statusCode),
          js.JSON.stringify(result) == """{"errors":["The handler was interrupted."]}""",
        )
      },
      test("a handler that finishes with Unit does not compile, and the message names both endings") {
        val viaMkHandler = typeCheckErrors("mkHandler(h = ZIO.unit)")
        val viaDefine    = typeCheckErrors("defineEventHandler(ZIO.unit)")
        assertTrue(
          viaMkHandler.exists(_.message.contains("finishes with Unit")),
          viaDefine.exists(_.message.contains("finishes with Unit")),
          viaDefine.exists(_.message.contains("ZHandler.sendNoContent")),
          viaDefine.exists(_.message.contains("NativeConverter")),
        )
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
      test("a body returned after the response was sent is dropped, with one warning") {
        for
            (result, warnings) <- capturingWarnings(invoke(mkHandler(h = ZIO.succeed("late")), fakeEvent(handled = true)))
        yield assertTrue(
          js.isUndefined(result),
          warnings.length == 1,
          warnings.head.contains("[zio-h3] GET /fake: the handler returned a value after the response was sent"),
        )
      },
      test("sendStream after the response was sent writes nothing and keeps the proof") {
        val event = fakeEvent(handled = true)
        for result <- invoke(mkHandler(h = ZHandler.sendStream(zio.stream.ZStream("x"))), event)
        yield assertTrue(js.isUndefined(result))
      },
      test("sendNoContent after the response was sent writes nothing and keeps the proof") {
        val event = fakeEvent(handled = true)
        for result <- invoke(mkHandler(h = ZHandler.sendNoContent), event)
        yield assertTrue(js.isUndefined(result), js.isUndefined(event.node.res.statusCode))
      },
      test("a proof without a response on a closed connection sends nothing") {
        val event = fakeEvent()
        event.node.res.destroyed = true
        for result <- invoke(mkHandler(h = responded), event)
        yield assertTrue(js.isUndefined(result), js.isUndefined(event.node.res.statusCode))
      },
      test("a proof without a response becomes a 500 error body, not a silent 204 or 404") {
        val event = fakeEvent()
        for result <- invoke(mkHandler(h = responded), event)
        yield assertTrue(
          event.node.res.statusCode.asInstanceOf[Int] == 500,
          js.JSON.stringify(result) ==
            """{"errors":["The handler finished without sending a response."]}""",
        )
      },
    ) @@ TestAspect.timeout(10.seconds)

    /** A minimal h3 event. `deliver` and the error boundary read `handled`, the log lines read
      * `method` and `path`, `setResponseStatus` writes to `node.res`, h3's `getRouterParams` reads
      * `context.params` (Changed by Claude Code on 2026-10-13: added for the `SafeError` test),
      * and the tests read nothing else.
      */
    private def fakeEvent(handled: Boolean = false): js.Dynamic =
      js.Dynamic.literal(
        handled = handled,
        method = "GET",
        path = "/fake",
        context = js.Dynamic.literal(params = js.Dynamic.literal()),
        node = js.Dynamic.literal(res = js.Dynamic.literal()),
      )

    /** A fake event whose response can be ended: `writeHead` and `end` flip `handled`, as Node's
      * `headersSent` and `writableEnded` do, and `$fetch` resolves to `undefined`.
      */
    private def respondingFakeEvent(): js.Dynamic =
        val event = fakeEvent()
        val res   = event.node.res
        res.writeHead = js.Any.fromFunction1 { (code: Int) =>
            res.statusCode = code
            event.handled = true
            ()
        }
        res.removeHeader = js.Any.fromFunction1((_: String) => ())
        res.end = js.Any.fromFunction0 { () =>
            event.handled = true
            ()
        }
        event.updateDynamic("$fetch")(
          js.Any.fromFunction1((_: js.Any) => js.Promise.resolve[js.Any](js.undefined.asInstanceOf[js.Any])),
        )
        event
    end respondingFakeEvent

    /** Runs `effect` while `console.warn` records its first argument, then restores it. */
    private def capturingWarnings[A](effect: Task[A]): Task[(A, List[String])] =
        val console  = js.Dynamic.global.console
        val original = console.warn
        val recorded = scala.collection.mutable.ListBuffer.empty[String]
        ZIO.acquireReleaseWith(
          ZIO.succeed:
              console.warn = js.Any.fromFunction1 { (message: js.Any) =>
                  recorded += message.toString
                  ()
              },
        )(_ => ZIO.succeed { console.warn = original })(_ => effect.map(a => (a, recorded.toList)))
    end capturingWarnings

    /** Calls the h3 handler with `event` and returns the value its promise resolves to. */
    private def invoke(handler: CommonEvHandler, event: js.Dynamic = fakeEvent()): Task[js.Any] =
      ZIO.fromPromiseJS(handler.asInstanceOf[js.Function1[js.Any, js.Promise[js.Any]]](event))
end HandlerResultSpec
