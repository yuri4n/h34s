package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.kyoh3.Shared
import dev.yuri4n.exts.zioh3.internal.{ZioCall, ZioExit}
import kyo.{Abort, Async, Frame, Scope, Sync, ZIOs, `<`}
import zio.{Exit, Runtime, Tag, Task, UIO, Unsafe, ZEnvironment, ZIO, ZLayer}

/** An application-scoped ZIO environment, such as a database pool, built once and shared by all
  * requests.
  *
  * The first `get` builds the layer; concurrent first calls share one build. A failed build is not
  * cached: the next `get` tries again. The layer's resources are released by `close`, or when Nitro
  * closes (the h34s Nitro plugin calls every registered shared resource). Built on the Kyo core's
  * `Shared`, so one cancelled request cannot cancel a build that other requests wait for.
  */
final class ZShared[R] private (shared: Shared[ZEnvironment[R]])(using tag: Tag[R]):
    /** The environment, built on first use. */
    def get: Task[ZEnvironment[R]] = ZIOs.run(shared.get)

    /** Provides the shared environment to a program that needs it. */
    def provide[R1, E, A](program: ZIO[R & R1, E, A]): ZIO[R1, E | Throwable, A] =
      get.flatMap(environment => program.provideSomeEnvironment[R1](_.union[R](environment)))

    /** Releases the layer's resources and waits for their finalizers. A later `get` builds it again. */
    def close: UIO[Unit] = ZIOs.run(shared.close)
end ZShared

object ZShared:
    /** Declares a shared layer. Nothing is built until the first `get` or `provide`. */
    def layer[E <: Throwable, R: Tag](layer: ZLayer[Any, E, R])(using
        runtime: Runtime[Any] = Runtime.default,
    ): ZShared[R] =
      new ZShared(Shared(build(runtime, layer)))

    /** Builds the layer into a ZIO scope that the shared resource's Kyo scope closes. */
    private def build[E <: Throwable, R](runtime: Runtime[Any], layer: ZLayer[Any, E, R])(using
        Frame,
    ): ZEnvironment[R] < (Async & Scope & Abort[Throwable]) =
      Sync.Unsafe.defer {
        val scope = Unsafe.unsafely(zio.Scope.unsafe.make)
        val call  = new ZioCall(runtime, layer.build(scope))
        Scope.ensure(call.stop.andThen(ZioCall.closeScope(runtime, scope, Exit.unit)))
          .andThen(Sync.Unsafe.defer(call.start()))
          .andThen(call.await)
          .map(exit => ZioExit.typed(exit))
      }
end ZShared
