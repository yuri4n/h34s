package dev.yuri4n.exts.zioh3

import org.getshaka.nativeconverter.{NativeConverter, ParseState}
import zio.*
import zio.stream.ZStream
import zio.test.*

import scala.concurrent.ExecutionContext
import scala.scalajs.js

object StreamLifecycleSpec extends ZIOSpecDefault:
    given ExecutionContext = ExecutionContext.global

    def spec = suite("Web Stream lifecycle")(
      test("does not drain upstream before consumption") {
        for
          produced <- Ref.make(0)
          first <- Promise.make[Nothing, Unit]
          stream = ZStream.repeatZIO(produced.updateAndGet(_ + 1).tap(_ => first.succeed(()))).take(100)
          reader = taskStreamToReadableStreamReader(stream)
          _ <- first.await
          _ <- ZIO.yieldNow.repeatN(20)
          count <- produced.get
          _ <- ZIO.fromPromiseJS(reader.cancel())
        yield assertTrue(count <= 1)
      },
      test("cancellation waits for the scoped finalizer") {
        for
          acquired <- Promise.make[Nothing, Unit]
          finalizing <- Promise.make[Nothing, Unit]
          release <- Promise.make[Nothing, Unit]
          finished <- Ref.make(0)
          stream = ZStream.unwrapScoped(
            ZIO.acquireRelease(acquired.succeed(()))(_ => finalizing.succeed(()) *> release.await *> finished.update(_ + 1))
              .as(ZStream.never: ZStream[Any, Throwable, String])
          )
          reader = taskStreamToReadableStreamReader(stream)
          _ <- acquired.await
          cancelling <- ZIO.fromPromiseJS(reader.cancel()).fork
          _ <- finalizing.await
          pending <- cancelling.poll
          _ <- release.succeed(())
          _ <- cancelling.join
          count <- finished.get
        yield assertTrue(pending.isEmpty, count == 1)
      },
      test("finalizes once after stream exhaustion") {
        for
          released <- Ref.make(0)
          stream = ZStream.unwrapScoped(ZIO.acquireRelease(ZIO.unit)(_ => released.update(_ + 1)).as(ZStream("x")))
          reader = taskStreamToReadableStreamReader(stream)
          value <- ZIO.fromPromiseJS(reader.read())
          end <- ZIO.fromPromiseJS(reader.read())
          _ <- ZIO.fromPromiseJS(reader.cancel())
          count <- released.get
        yield assertTrue(!value.done, end.done, count == 1)
      },
      test("closes scope if native conversion throws") {
        final case class Broken(value: String)
        given NativeConverter[Broken] with
          extension (value: Broken) def toNative: js.Any = throw new IllegalArgumentException("conversion failed")
          def fromNative(state: ParseState): Broken = Broken("")
        for
          released <- Ref.make(0)
          stream = ZStream.unwrapScoped(ZIO.acquireRelease(ZIO.unit)(_ => released.update(_ + 1)).as(ZStream(Broken("x"))))
          reader = taskStreamToReadableStreamReader(stream)
          result <- ZIO.fromPromiseJS(reader.read()).either
          count <- released.get
        yield assertTrue(result.isLeft, count == 1)
      },
    ) @@ TestAspect.timeout(10.seconds)
end StreamLifecycleSpec
