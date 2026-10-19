package dev.yuri4n.exts.kyoh3.internal

import dev.yuri4n.exts.h3.internal.{H3Event, Nitro}
import dev.yuri4n.exts.kyoh3.{Fetch, FetchError, FetchRequest, FetchResponse}
import kyo.{Abort, AllowUnsafe, Async, Chunk, Emit, Frame, Kyo, Loop, Scope, Stream, Sync, `<`}

import scala.scalajs.js

// claude 2026-10-19: created: the default Fetch, over Nitro's event.fetch (fetchWithEvent over localFetch), captured at request start
/** The server-side `$fetch` of one request, over Nitro's `event.fetch`.
  *
  * Nitro 2.13.1 sets `event.fetch` in its h3 `onRequest`, before any route handler:
  * `fetchWithEvent(event, req, init, { fetch: localFetch })`. It forwards the incoming headers
  * (cookies and authorization included) and answers a path that starts with `/` in-process through
  * node-mock-http. Unlike `event.$fetch` (ofetch), it does not buffer, retry or throw on an error
  * status, so the head arrives as soon as the callee's handler settles.
  */
private[exts] final class NitroFetch(event: H3Event) extends Fetch:
    private val native: js.Any = event.asInstanceOf[js.Dynamic].fetch

    def raw(request: FetchRequest)(using Frame): FetchResponse < (Async & Abort[FetchError] & Scope) =
      if !Nitro.isFunction(native) then
          Abort.panic(IllegalStateException("Server-side $fetch needs Nitro: this h3 event has no fetch function"))
      else
          Sync.Unsafe.defer {
            val call = new NitroFetch.Call(native.asInstanceOf[js.Function2[String, js.Any, js.Promise[js.Dynamic]]], request)
            // Registered before the wait: a caller that is interrupted before the head arrives still
            // cancels the body when it arrives.
            Scope.ensure(call.release).andThen(call.head)
          }
end NitroFetch

private[exts] object NitroFetch:
    // claude 2026-10-19: created: the entity headers that fetchWithEvent forwards from the incoming request
    /** `fetchWithEvent` forwards the incoming request's `content-type` and `content-length` even
      * when this request has another body or none. An empty value overrides them; node-mock-http
      * drops empty header values, so the callee sees only what this request sends.
      */
    private val entityHeaders: List[String] = List("content-type", "content-length")

    private[internal] final class Call(
        fetch: js.Function2[String, js.Any, js.Promise[js.Dynamic]],
        request: FetchRequest,
    )(using AllowUnsafe):
        private var response: js.Dynamic = null
        private var reader: js.Dynamic   = null
        private var finished             = false

        private val pending: js.Promise[js.Dynamic] =
          try fetch(request.url, init)
          catch case error: Throwable => js.Promise.reject(error)

        pending.`then`[Unit]((arrived: js.Dynamic) => { response = arrived; () }, js.undefined)

        private def init: js.Any =
          val headers = js.Dictionary.empty[String]
          if request.url.startsWith("/") then entityHeaders.foreach(name => headers(name) = "")
          request.body.foreach { body =>
            headers("content-type") = body.contentType
            headers("content-length") = utf8Length(body.text).toString
          }
          // A plain object: fetchWithEvent spreads init.headers, which drops a Headers instance.
          request.headers.foreach((name, value) => headers(name.toLowerCase) = value)
          val init = js.Dynamic.literal(method = request.method, headers = headers)
          request.body.foreach(body => init.body = body.text)
          init

        def head(using Frame): FetchResponse < (Async & Abort[FetchError]) =
          Abort.run[Throwable](Interop.await(pending)).map(_.foldError(
            arrived =>
              Sync.defer {
                val status  = arrived.status.asInstanceOf[Int]
                val headers = headersOf(arrived)
                if FetchResponse.isStreamed(headers) then Kyo.lift(FetchResponse.Streamed(status, headers, body))
                else
                    Abort.run[Throwable](Interop.await(arrived.text().asInstanceOf[js.Promise[String]])).map(_.foldError(
                      text =>
                        finished = true
                        FetchResponse.Plain(status, headers, text)
                      ,
                      error => Abort.fail(FetchError.Failed(message(error.failureOrPanic))),
                    ))
              },
            error => Abort.fail(FetchError.Failed(message(error.failureOrPanic))),
          ))

        /** The body as text chunks, read once. Each read is one pull of the callee's stream. */
        private def body(using Frame): Stream[String, Async & Scope] =
          Stream[String, Async] {
            Sync.defer {
              val native = response.body
              if native == null || js.isUndefined(native) then
                  finished = true
                  Kyo.unit
              else if reader != null then
                  Abort.panic(IllegalStateException("A streamed $fetch body can be read only once"))
              else
                  reader = native.getReader()
                  val decoder = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)()
                  Loop.foreach {
                    awaitOrPanic(reader.read().asInstanceOf[js.Promise[js.Dynamic]]).map { step =>
                      if step.done.asInstanceOf[Boolean] then
                          finished = true
                          val tail = decoder.decode().asInstanceOf[String]
                          if tail.isEmpty then Loop.done else Emit.valueWith(Chunk(tail))(Loop.done)
                      else
                          val value = step.value
                          val text  =
                            if js.typeOf(value) == "string" then value.asInstanceOf[String]
                            else decoder.decode(value, js.Dynamic.literal(stream = true)).asInstanceOf[String]
                          if text.isEmpty then Loop.continue else Emit.valueWith(Chunk(text))(Loop.continue)
                    }
                  }
            }
          }

        // claude 2026-10-19: created: the caller-scope finalizer that cancels an unread body (dropped Streamed body test)
        /** Cancels a body that was not read to its end and waits for the cancel. For an h34s callee
          * the cancel resolves after the callee's producer and request scope finished.
          */
        def release(using Frame): Unit < Async =
          Sync.defer {
            if finished then None
            else if response == null then
                // The head has not arrived: cancel the body when it does, without waiting.
                pending.`then`[Unit]((arrived: js.Dynamic) => { cancel(arrived, null); () }, js.undefined)
                None
            else Some(cancel(response, reader))
          }.map {
            case None          => Kyo.unit
            case Some(promise) => Abort.run[Throwable](Interop.await(promise)).unit
          }

        private def cancel(arrived: js.Dynamic, locked: js.Dynamic): js.Promise[Unit] =
          finished = true
          val target = if locked != null then locked else arrived.body
          if target == null || js.isUndefined(target) || (locked == null && Nitro.isTrue(target.locked)) then
              js.Promise.resolve[Unit](())
          else
              try target.cancel("The caller's scope closed").asInstanceOf[js.Promise[Unit]]
              catch case error: Throwable => js.Promise.reject(error).asInstanceOf[js.Promise[Unit]]
    end Call

    private def headersOf(response: js.Dynamic): Map[String, String] =
      val collected = scala.collection.mutable.Map.empty[String, String]
      response.headers.forEach(((value: String, name: String) => collected(name.toLowerCase) = value): js.Function2[String, String, Unit])
      collected.toMap

    private def utf8Length(text: String): Int =
      js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)().encode(text).length.asInstanceOf[Int]

    private def message(error: Throwable): String =
      Option(error.getMessage).getOrElse(error.getClass.getName)

    private def awaitOrPanic[A](promise: => js.Promise[A])(using Frame): A < Async =
      Abort.run[Throwable](Interop.await(promise)).map(_.foldError(value => value, error => Abort.panic(error.failureOrPanic)))
end NitroFetch
