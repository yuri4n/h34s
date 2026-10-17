package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.kyoh3.NitroGlobal
import dev.yuri4n.exts.zioh3.ZioTesting.*
import zio.*
import zio.test.*

import scala.scalajs.js

/** Application-scoped ZIO environments over the core's `Shared` (C10 for ZIO). */
object ZSharedSpec extends ZIOSpecDefault:
    final case class Pool(id: Int)

    private final class Counters:
        var built    = 0
        var released = 0

    /** A pool layer whose release is asynchronous and whose build waits for `gate`. */
    private def pool(counters: Counters, gate: UIO[Unit] = ZIO.unit): ZShared[Pool] =
      ZShared.layer(ZLayer.scoped(
        ZIO.acquireRelease(gate *> ZIO.succeed { counters.built += 1; Pool(counters.built) })(_ =>
          ZIO.yieldNow *> ZIO.succeed { counters.released += 1 },
        ),
      ))

    def spec = suite("ZShared")(
      test("concurrent first gets share one build; close releases once and waits for the finalizer") {
        val counters = Counters()
        for
          gate <- Promise.make[Nothing, Unit]
          shared = pool(counters, gate.await)
          first <- shared.get.fork
          second <- shared.get.fork
          _ <- turns(2)
          _ <- gate.succeed(())
          a <- first.join
          b <- second.join
          c <- shared.get
          _ <- shared.close
          releasedAfterClose = counters.released
          _ <- shared.close
        yield assertTrue(
          List(a, b, c).map(_.get[Pool]) == List(Pool(1), Pool(1), Pool(1)),
          counters.built == 1, releasedAfterClose == 1, counters.released == 1,
        )
      },
      test("a waiter that is interrupted does not cancel the shared build") {
        val counters = Counters()
        for
          gate <- Promise.make[Nothing, Unit]
          shared = pool(counters, gate.await)
          impatient <- shared.get.fork
          patient <- shared.get.fork
          _ <- turns(2)
          _ <- impatient.interrupt
          _ <- gate.succeed(())
          value <- patient.join
          _ <- shared.close
        yield assertTrue(value.get[Pool] == Pool(1), counters.built == 1, counters.released == 1)
      },
      test("a failed build is not cached and keeps its typed error") {
        var attempts = 0
        val error = IllegalStateException("database down")
        val shared = ZShared.layer(ZLayer.fromZIO(
          ZIO.suspendSucceed { attempts += 1; if attempts == 1 then ZIO.fail(error) else ZIO.succeed(Pool(attempts)) },
        ))
        for
          failed <- shared.get.either
          succeeded <- shared.get
          _ <- shared.close
        yield assertTrue(failed.left.exists(_ eq error), succeeded.get[Pool] == Pool(2), attempts == 2)
      },
      test("a later get after close builds again") {
        val counters = Counters()
        val shared = pool(counters)
        for
          _ <- shared.get
          _ <- shared.close
          again <- shared.get
          _ <- shared.close
        yield assertTrue(again.get[Pool] == Pool(2), counters.released == 2)
      },
      test("provide supplies the shared environment to a handler program") {
        val counters = Counters()
        val shared = pool(counters)
        val handler = ZHandler.define(request => shared.provide(ZIO.serviceWith[Pool](pool => s"${request.path} ${pool.id}")))
        for
          first <- Call("/a").invoke(handler)
          second <- Call("/b").invoke(handler)
          _ <- shared.close
        yield assertTrue(
          data(first).asInstanceOf[String] == "/a 1", data(second).asInstanceOf[String] == "/b 1",
          counters.built == 1, counters.released == 1,
        )
      },
      test("the Nitro close hook releases every built ZShared value") {
        val closers = scala.collection.mutable.LinkedHashSet.empty[js.Function0[js.Any]]
        val onClose: js.Function1[js.Function0[js.Any], js.Function0[js.Any]] = close =>
          closers += close
          () => closers.remove(close)
        val first = Counters()
        val second = Counters()
        val one = pool(first)
        val two = pool(second)
        (for
          _ <- ZIO.succeed(NitroGlobal.install(js.Dynamic.literal(version = 1, onClose = onClose)))
          _ <- one.get
          _ <- two.get
          registered = closers.size
          _ <- ZIO.foreachDiscard(closers.toList)(close => ZIO.fromPromiseJS(close().asInstanceOf[js.Promise[js.Any]]))
          _ <- one.close
        yield assertTrue(registered == 2, first.released == 1, second.released == 1, closers.isEmpty))
          .ensuring(ZIO.succeed(NitroGlobal.remove()))
      },
    ) @@ TestAspect.sequential @@ TestAspect.timeout(45.seconds)
end ZSharedSpec
