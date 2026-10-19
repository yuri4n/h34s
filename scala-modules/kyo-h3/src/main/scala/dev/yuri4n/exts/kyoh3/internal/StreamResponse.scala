package dev.yuri4n.exts.kyoh3.internal

import dev.yuri4n.exts.h3.internal.{H3, H3Event, Nitro}
import dev.yuri4n.exts.h3.{ResponseHead, Sent}
import dev.yuri4n.exts.kyoh3.HandlerTransport
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
    // claude 2026-10-19: created: the marker header that sendStream sets (decision 3's detection rule)
    /** Set on every response that `sendStream` writes, so a server-side `$fetch` can tell a stream
      * from a buffered body by its head alone.
      */
    val marker: String = "x-h34s-stream"

    // claude 2026-10-19: changed: send takes the request's HandlerTransport and the program's head, returns Sent, and supports a socketless response (Nitro local fetch) instead of rejecting it
    /** Sends a stream through h3 and gives the `Sent` proof. Runs inside the handler program, so the
      * request scope stays open until the stream ends or is cancelled.
      *
      * With a Node socket, the head is written just before the first chunk (or before an empty
      * stream closes), so a failure before that still uses the failure rule: the success head is
      * cleared and the failure is raised for the core to render. A failure after the head destroys
      * the socket and is logged; the response then belongs to the transport.
      *
      * Without a socket (Nitro's in-process local fetch, node-mock-http), the head is written before
      * h3's `sendStream`, because the local fetch copies status and headers when the h3 handler
      * promise settles. That promise is settled at once; the program then waits until the caller has
      * read the stream to its end or cancelled it. Chunks are UTF-8 bytes, because a Web `Response`
      * accepts only `Uint8Array` chunks.
      */
    def send(transport: HandlerTransport, head: ResponseHead, chunks: Stream[js.Any, StreamEffects])(using
        Frame,
    ): Sent < (Async & Abort[Throwable] & Scope) =
      Sync.Unsafe.defer {
        if transport.event == null then
            Abort.panic(IllegalStateException("Response.sendStream needs a request served by KHandler.define"))
        else if transport.state != HandlerTransport.State.Open then
            Abort.panic(IllegalStateException("The response was already sent: call sendStream once, as the handler's result"))
        else
            transport.state = HandlerTransport.State.Streaming
            val event      = transport.event
            val committed  = ResponseHead.forSuccess(transport.options.defaults, head).withHeader(marker, "1")
            val streamHead = Rendering.withDefaultContentType(event, committed, Rendering.textContentType)
            val socket     = event.node.res.socket
            if socket == null || js.isUndefined(socket) then socketless(transport, streamHead, chunks)
            else throughSocket(transport, streamHead, chunks)
      }

    // claude 2026-10-19: changed: the former send body (lazy head, request-scope cancel, failure split), now ending in Sent
    private def throughSocket(transport: HandlerTransport, streamHead: ResponseHead, chunks: Stream[js.Any, StreamEffects])(using
        Frame,
        AllowUnsafe,
    ): Sent < (Async & Abort[Throwable] & Scope) =
      val event   = transport.event
      var applied = false
      val bridge  = new Bridge(
        chunks,
        () =>
          applied = true
          Rendering.writeHead(event, streamHead),
        bytes = false,
        afterCancel = None,
      )
      // Registered in the request scope: a disconnect or failure cancels the producer and waits for
      // its finalizers before the request scope continues closing.
      Scope.ensure(bridge.cancelAndAwait).andThen(
        Abort.run[Throwable](Interop.await(H3.sendStream(event, bridge.readable))).map(_.foldError(
          _ => Sync.defer(sentBy(transport)),
          // The Web stream only carries a Throwable; the producer's own result says whether it was
          // an explicit failure or a defect. When the writer side failed (the pipe cancelled the
          // stream), the producer only saw an interrupt: report the write error.
          error =>
            val failure: Result.Error[Throwable] =
              bridge.producerError.filter(_ => !bridge.cancelledByConsumer).getOrElse(Result.Panic(error.failureOrPanic))
            Sync.defer {
              if headersSent(event) then
                  // The client already has a status and part of the body: end the connection so
                  // the response cannot look complete.
                  event.node.res.destroy()
                  Rendering.logDefect(Rendering.describe(failure.failureOrPanic))
                  val sent: Sent < Abort[Throwable] = sentBy(transport)
                  sent
              else
                  // The head may be applied already (before the first chunk) but not sent: the
                  // failure response must not carry success-only headers or cookies.
                  if applied then Rendering.clearHead(event, streamHead)
                  transport.state = HandlerTransport.State.Open
                  Abort.error(failure)
            }
          ,
        )),
      )
    end throughSocket

    // claude 2026-10-19: created: the socketless path: eager head, early settle, wait for the caller to finish or cancel
    private def socketless(transport: HandlerTransport, streamHead: ResponseHead, chunks: Stream[js.Any, StreamEffects])(using
        Frame,
        AllowUnsafe,
    ): Sent < (Async & Abort[Throwable] & Scope) =
      val event  = transport.event
      val bridge = new Bridge(chunks, () => (), bytes = true, afterCancel = Some(() => transport.finished))
      Sync.defer(Rendering.writeHead(event, streamHead)).andThen(
        Scope.ensure(bridge.cancelAndAwait).andThen(
          Abort.run[Throwable](Interop.await(H3.sendStream(event, bridge.readable))).map(_.foldError(
            _ =>
              Sync.defer {
                transport.state = HandlerTransport.State.Sent
                transport.settleEarly()
              }.andThen(bridge.completion).map { result =>
                Sync.defer {
                  // The caller has the head already: a stream failure can only end its reads.
                  result.foldError(_ => (), error => if !bridge.cancelledByConsumer then Rendering.logDefect(Rendering.describe(error.failureOrPanic)))
                  sentBy(transport)
                }
              },
            error =>
              Sync.defer {
                Rendering.clearHead(event, streamHead)
                transport.state = HandlerTransport.State.Open
                Abort.error(error)
              },
          )),
        ),
      )
    end socketless

    private def sentBy(transport: HandlerTransport): Sent =
      transport.state = HandlerTransport.State.Sent
      new Sent(transport)

    private def headersSent(event: H3Event): Boolean =
      Nitro.isTrue(event.node.res.asInstanceOf[js.Dynamic].headersSent)

    /** A Web `ReadableStream` over a Kyo stream, for callers that consume it directly. */
    def readable(chunks: Stream[js.Any, StreamEffects], beforeFirstChunk: () => Unit = () => ())(using
        Frame,
    ): js.Any =
      import AllowUnsafe.embrace.danger
      new Bridge(chunks, beforeFirstChunk, bytes = false, afterCancel = None).readable

    // claude 2026-10-19: changed: bytes (UTF-8 chunks for Web consumers) and afterCancel (a socketless cancel waits for the request)
    private final class Bridge(
        chunks: Stream[js.Any, StreamEffects],
        beforeFirstChunk: () => Unit,
        bytes: Boolean,
        afterCancel: Option[() => js.Promise[Unit]],
    )(using
        Frame,
        AllowUnsafe,
    ):
        private val requests   = Channel.Unsafe.init[Unit](1).safe
        private val deliveries = Channel.Unsafe.init[Chunk[js.Any]](1).safe
        private var committed  = false
        private var cancelled  = false
        private lazy val encoder = js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)()
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
                    chunk.foreach(value => controller.enqueue(encode(value)))
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

        // claude 2026-10-19: created: UTF-8 bytes for Web consumers; other values pass through
        private def encode(value: js.Any): js.Any =
          if bytes && js.typeOf(value) == "string" then encoder.encode(value) else value

        // claude 2026-10-19: created: the producer's outcome, after the stream ended or was cancelled
        def completion(using Frame): Result[Throwable, Unit] < Async = producer.await

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
              val stopped = producer.promise()
              afterCancel.fold(stopped)(after => stopped.`then`[Unit]((_: Unit) => (after(): Unit | js.Thenable[Unit])))
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
