package dev.yuri4n.exts.zioh3.internal

import dev.yuri4n.exts.kyoh3
import dev.yuri4n.exts.kyoh3.internal.Supervised
import dev.yuri4n.exts.kyoh3.{FetchError, FetchRequest}
import dev.yuri4n.exts.zioh3.{Fetch, FetchResponse}
import kyo.{Abort, AllowUnsafe, Async, Channel, Chunk, Closed, Frame, Maybe, Result, Scope, Stream, Sync, `<`}
import zio.stream.ZStream
import zio.{IO, UIO, ZIO}

// claude 2026-10-20: created: the ZIO $fetch over the Kyo core's Fetch, bridged at the boundary (ADR-0005 gap 6, ADR-0006)
/** The Kyo core's `$fetch` of one request, as the ZIO `Fetch` service.
  *
  * The Kyo `raw` runs on a supervised Kyo worker (`KyoCall.scoped`). Its Kyo scope holds the
  * call's release, which cancels an unread body and waits for the callee's whole request cleanup;
  * it stays open until the caller's ZIO `Scope` closes, and only for a streamed response. A
  * streamed body becomes a `ZStream` whose pulls each run one Kyo pull (`KyoPull`). kyo-zio's
  * `ZIOs.get` and `ZStreams.get` are not used: their interruption does not wait.
  */
private[exts] final class KyoFetch(kyo: kyoh3.Fetch) extends Fetch:
    def raw(request: FetchRequest): ZIO[zio.Scope, FetchError, FetchResponse] =
      KyoCall.scoped(kyo.raw(request), keepOpen = _.isInstanceOf[kyoh3.FetchResponse.Streamed])
        .refineOrDie { case error: FetchError => error }
        .map {
          case kyoh3.FetchResponse.Plain(status, headers, text)    => FetchResponse.Plain(status, headers, text)
          case kyoh3.FetchResponse.Streamed(status, headers, body) => FetchResponse.Streamed(status, headers, KyoPull.stream(body))
        }
end KyoFetch

// claude 2026-10-20: created: a Kyo stream of a $fetch body as a ZStream, demand-gated like the core's sendStream bridge (ADR-0005 gap 6)
/** A Kyo stream read from ZIO, one chunk per pull.
  *
  * One producer fiber, under a never-interrupted supervisor (`Supervised`), runs the Kyo stream
  * and waits for a request before each chunk, through two capacity-1 channels, as the core's
  * `StreamResponse` bridge does. Each ZIO pull runs one Kyo step with `KyoCall.run`, whose
  * interruption waits. When the `ZStream` ends or is interrupted, its scope interrupts the producer
  * and waits until the producer's scope has closed.
  */
private[internal] object KyoPull:
    def stream(body: => Stream[String, Async & Scope & Abort[FetchError]])(using Frame): ZStream[Any, FetchError, String] =
      ZStream.unwrapScoped[Any](
        ZIO.acquireRelease(ZIO.succeed(new Producer(body)))(_.close).map { producer =>
          ZStream.repeatZIOChunkOption(producer.next)
        },
      )

    private final class Producer(body: => Stream[String, Async & Scope & Abort[FetchError]])(using Frame):
        import AllowUnsafe.embrace.danger

        private val requests   = Channel.Unsafe.init[Unit](1).safe
        private val deliveries = Channel.Unsafe.init[Chunk[String]](1).safe

        private val producer = new Supervised[Unit](
          requests.take.andThen(body.foreachChunk(chunk => deliveries.put(chunk).andThen(requests.take))),
        )
        producer.onComplete { _ =>
          requests.unsafe.close()
          deliveries.unsafe.close()
          ()
        }

        /** One Kyo step: a request, then the next chunk; `Absent` at the end of the stream. */
        private def step: Maybe[Chunk[String]] < (Async & Abort[Throwable]) =
          Abort.run[Closed](requests.put(()).andThen(deliveries.take)).map {
            case Result.Success(chunk) => Maybe(chunk)
            case _                     =>
              producer.await.map(_.foldError(_ => Maybe.empty[Chunk[String]], error => Abort.error(error)))
          }

        /** The next chunk for `ZStream.repeatZIOChunkOption`: `None` at the end, the body's typed
          * `FetchError`, or a defect.
          */
        def next: IO[Option[FetchError], zio.Chunk[String]] =
          KyoCall.run(step)
            .refineOrDie { case error: FetchError => Some(error) }
            .flatMap {
              case Maybe.Present(chunk) => ZIO.succeed(zio.Chunk.fromIterable(chunk))
              case _                    => ZIO.fail(None)
            }

        /** Interrupts the producer and waits until its scope has closed. */
        def close: UIO[Unit] = KyoCall.run(Sync.defer(producer.interrupt()).andThen(producer.await.unit)).orDie
    end Producer
end KyoPull
