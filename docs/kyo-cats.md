# Use Cats Effect libraries from Kyo

Use the optional `kyo-cats-ext` module when your Kyo application needs a library whose API returns `cats.effect.IO`, or when you are migrating existing Cats Effect code. Keep native Kyo libraries on Kyo. In particular, evaluate Kyo SQL for a new Kyo database service; installing this bridge does not select Skunk or a database for your application.

This module is development work at `dev.yuri4n:kyo-cats-ext_sjs1_3:0.1.0-alpha.2-SNAPSHOT`. It is not part of the published `0.1.0-alpha.1` release and has not been published to GitHub Packages. Its only runtime dependencies are Kyo and Cats Effect. It does not require h3, ZIO, NuxtHub, Skunk, or a converter.

## Try the development module

Select JDK 25, sbt 1.11.7, Scala 3.9.0, and Scala.js 1.22.0. From a checkout containing this module, run:

```sh
sbt 'kyoCatsExt/test' 'kyoCatsExt/publishLocal'
```

Then add the locally staged artifact to your Scala.js application's `build.sbt`:

```scala
libraryDependencies +=
  "dev.yuri4n" %%% "kyo-cats-ext" % "0.1.0-alpha.2-SNAPSHOT"
```

This selects Kyo `1.0.0-RC7` and Cats Effect `3.6.3`. Keep the application compiler on Scala 3.9.0. The module does not change the existing private Maven alpha, npm package, or release tags. See [releases](releases.md) for the separate release process.

## Keep resource ownership inside IO

Import the bridge and pass an entire Cats `Resource.use` program:

```scala
import cats.effect.{IO, Resource}
import dev.yuri4n.exts.kyocats.CatsEffect
import kyo.*

def useClient[A, B](client: Resource[IO, A])(
    use: A => IO[B],
)(using Frame): B < (Async & Scope & Abort[Throwable]) =
  CatsEffect.run(client.use(use))
```

Define your client resource, connection settings, queries, and services in your application. Supply those services with `Env` before defining your handler. A `KHandler.define` program already has the effects required by `CatsEffect.run`; you do not need a Promise conversion or a Cats runtime in endpoint code.

Keep `Resource.use` inside the IO passed to `run`. Returning a client from `use` closes it before the Kyo continuation can use it. This bridge does not transfer Cats resources into Kyo, establish a connection pool, or turn an IO stream into an HTTP stream.

`run` evaluates its by-name IO once each time the Kyo computation runs. Constructing a Kyo computation does not start the IO. The implementation uses Cats Effect's global Scala.js runtime and its supported cancelable Future API; it does not depend on an older `kyo-cats` artifact or expose a second runtime to application handlers. Custom Cats runtime injection is not included in this API.

## Cancellation and cleanup

Starting the IO and registering its Kyo scope finalizer happen atomically. The finalizer calls Cats cancellation and waits for it to finish. A normal IO result also waits for the Cats `Resource` release before reaching the Kyo continuation.

Keep the enclosing Kyo scope alive until cleanup completes. `KHandler` already supervises a scoped request worker and joins its cleanup when a client disconnects. For non-HTTP use, follow Kyo's scoped-fiber ownership rules: interrupt a worker and join its owning scope. Directly interrupting the `Scope.run` supervisor in Kyo RC7 starts detached abnormal-exit cleanup, so the supervisor's Future alone is not a cleanup barrier.

Cancellation remains cooperative. An uncancelable Cats acquire, use, or release section can delay completion. If a release fails during cancellation, Cats/Kyo diagnostics report that failure; it does not replace an already interrupted request with a new response. Choose connection, query, and request deadlines in your application with those semantics in mind.

## Handle errors explicitly

IO construction failures, IO failures, ordinary resource-release failures, and Cats self-cancellation enter `Abort[Throwable]`. The bridge wraps them in `RuntimeException("Cats Effect operation failed", originalCause)`. The cause remains available for server diagnostics. An exception carrying an HTTP-safe marker does not accidentally approve an infrastructure error message for a client.

For expected domain outcomes, return a model such as `Either[DomainError, Value]` from IO, then handle it explicitly in Kyo. Choose the safe response there. Do not use an infrastructure exception's message as approved response data. With `KHandler`, an unhandled bridge failure uses the normal opaque production HTTP 500 response; development diagnostics keep its cause.

## Validate the boundary

The module tests cover lazy and repeatable evaluation, normal cleanup, construction/acquisition/use/release failures, Cats self-cancellation, and interruption during acquisition, use, and uncancelable release. Gated cleanup checks verify that the owning Kyo scope waits for all nested releases, in reverse acquisition order, exactly once.

The reusable-library consumer check stages Maven artifacts in a disposable repository, then compiles and runs an independent Cats/Kyo consumer. It checks that the consumer has no h3, ZIO, Skunk, or other h34s module, and verifies that a Cats resource closes before the result is returned. Run `node scripts/check-consumers.mjs` after selecting the repository toolchain. These checks do not establish support for another Kyo/Cats release, Nix, or a custom runtime.
