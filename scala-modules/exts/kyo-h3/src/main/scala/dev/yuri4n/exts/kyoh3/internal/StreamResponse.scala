package dev.yuri4n.exts.kyoh3.internal

import dev.yuri4n.exts.h3.internal.{H3, H3Event}
import dev.yuri4n.exts.kyoh3.ResponseBody.StreamEffects
import kyo.{Abort, AllowUnsafe, Async, Channel, Chunk, Closed, Fiber, Frame, Result, Scope, Stream, Sync, `<`}

import scala.scalajs.js

/** Kyo streams as pull-driven Web `ReadableStream`s, and their delivery through h3.
  *
  * One producer fiber owns the stream and its resources. It advances only on demand: each `pull`
  * requests one chunk, and the producer does not start the next chunk until the next request.
  * Cancelling the Web stream interrupts the producer and waits for the stream's finalizers.
  */
private[exts] object StreamResponse:
    /** Sends a stream through h3. `beforeFirstChunk` writes the response head; it runs once,
      * before the first chunk or before an empty stream closes. Fails before acquiring any stream
      * resource when the response has no socket (Nitro local fetch).
      */
    def send(event: H3Event, chunks: Stream[js.Any, StreamEffects], beforeFirstChunk: () => Unit)(using
        Frame,
    ): Unit < (Async & Abort[Throwable] & Scope) =
      Sync.defer {
        val socket = event.node.res.socket
        if socket == null || js.isUndefined(socket) then
            Abort.fail(UnsupportedOperationException("Streaming requires a Node HTTP response"))
        else
            Sync.Unsafe.defer {
              val bridge = new Bridge(chunks, beforeFirstChunk)
              // Registered in the request scope: a disconnect or failure cancels the producer and
              // waits for its finalizers before the request scope continues closing.
              Scope.ensure(bridge.cancelAndAwait).andThen(
                Abort.run[Throwable](Interop.await(H3.sendStream(event, bridge.readable))).map(_.foldError(
                  _ => (),
                  // The Web stream only carries a Throwable; the producer's own result says whether
                  // it was an explicit failure or a defect. When the writer side failed (the pipe
                  // cancelled the stream), the producer only saw an interrupt: report the write error.
                  error =>
                    bridge.producerError.filter(_ => !bridge.cancelledByConsumer)
                      .fold(Abort.panic(error.failureOrPanic))(Abort.error(_)),
                )),
              )
            }
      }

    /** A Web `ReadableStream` over a Kyo stream, for callers that consume it directly. */
    def readable(chunks: Stream[js.Any, StreamEffects], beforeFirstChunk: () => Unit = () => ())(using
        Frame,
    ): js.Any =
      import AllowUnsafe.embrace.danger
      new Bridge(chunks, beforeFirstChunk).readable

    private final class Bridge(chunks: Stream[js.Any, StreamEffects], beforeFirstChunk: () => Unit)(using
        Frame,
        AllowUnsafe,
    ):
        private val requests   = Channel.Unsafe.init[Unit](1).safe
        private val deliveries = Channel.Unsafe.init[Chunk[js.Any]](1).safe
        private var committed  = false
        private var cancelled  = false
        var producerError: Option[Result.Error[Throwable]] = None

        /** The Web stream's reader cancelled it, for example because writing to the response failed. */
        var cancelledByConsumer = false

        private val producer = new Supervised[Unit](
          requests.take.andThen(
            chunks.foreachChunk(chunk => deliveries.put(chunk).andThen(requests.take)),
          ),
        )
        producer.onComplete { result =>
          result.foldError(_ => (), error => producerError = Some(error))
          requests.unsafe.close()
          deliveries.unsafe.close()
          ()
        }

        private def commit(): Unit =
          if !committed then
              committed = true
              beforeFirstChunk()

        private def pull(controller: js.Dynamic): Unit < Async =
          Abort.run[Closed](requests.put(()).andThen(deliveries.take)).map {
            case Result.Success(chunk) =>
              Sync.defer {
                if !cancelled then
                    commit()
                    chunk.foreach(value => controller.enqueue(value))
              }
            case _                     =>
              producer.await.map { result =>
                Sync.defer {
                  if !cancelled then
                      result.foldError(
                        _ =>
                          commit()
                          controller.close()
                          ()
                        ,
                        error =>
                          producerError = Some(error)
                          controller.error(error.failureOrPanic.asInstanceOf[js.Any])
                          ()
                        ,
                      )
                }
              }
          }

        def cancelAndAwait(using Frame): Unit < Async =
          Sync.defer {
            cancelled = true
            producer.interrupt()
          }.andThen(producer.await.unit)

        lazy val readable: js.Any =
          val source = js.Dynamic.literal(
            pull = (controller: js.Dynamic) => run(controller)(pull(controller)),
            cancel = (_: js.Any) => {
              cancelled = true
              cancelledByConsumer = true
              producer.interrupt()
              producer.promise()
            },
          )
          js.Dynamic.newInstance(js.Dynamic.global.ReadableStream)(source)

        /** Runs one pull. Unexpected failures error the Web stream instead of rejecting `pull`. */
        private def run(controller: js.Dynamic)(program: Unit < Async): js.Promise[Unit] =
          new js.Promise[Unit]((resolve, _) =>
            Fiber.Unsafe.init(Abort.run[Throwable](program)).onComplete { outcome =>
              outcome.fold(
                result =>
                  result.eval.fold(
                    _ => (),
                    error => fail(controller, error),
                    error => fail(controller, error),
                  ),
                _ => (),
                error => fail(controller, error),
              )
              resolve(())
            },
          )

        private def fail(controller: js.Dynamic, error: Throwable): Unit =
          try controller.error(error.asInstanceOf[js.Any])
          catch case _: Throwable => ()
    end Bridge
end StreamResponse
