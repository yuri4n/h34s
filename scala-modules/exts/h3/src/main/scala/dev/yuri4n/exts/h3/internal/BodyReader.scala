package dev.yuri4n.exts.h3.internal

import scala.scalajs.js
import scala.scalajs.js.typedarray.{ArrayBuffer, Uint8Array}

/** Why a bounded body read did not produce bytes. */
private[exts] enum BodyReadFailure:
    /** The body is larger than the limit. At most `limit` bytes were buffered. */
    case TooLarge

    /** The transport failed while reading (for example, the client went away). */
    case Failed(cause: Throwable)

/** Reads a request body into memory without buffering more than `limit` bytes.
  *
  * The callback runs exactly once. `None` means the request has no body. Bodies that upstream
  * middleware already buffered (h3's raw-body cache, `req.rawBody`, `req.body`, Web request
  * bodies) are honoured, as h3's own `readRawBody` does.
  */
private[exts] object BodyReader:
    type Outcome = Either[BodyReadFailure, Option[Uint8Array]]

    def read(event: H3Event, limit: Long)(done: Outcome => Unit): Unit =
      val once = new Once(done)
      try
          val dynamicEvent = event.asInstanceOf[js.Dynamic]
          val request      = event.node.req.asInstanceOf[js.Dynamic]
          prebuffered(dynamicEvent, request) match
              case Some(value) =>
                js.Promise.resolve[js.Any](value).`then`[Unit](
                  resolved => fromValue(resolved, limit, once),
                  error => once(Left(BodyReadFailure.Failed(throwable(error)))),
                )
                ()
              case None        =>
                val headers  = event.node.req.headers
                val length   = contentLength(headers)
                val chunked  = isChunked(headers)
                if length.forall(_ <= 0) && !chunked then once(Right(None))
                else if length.exists(_ > limit) then once(Left(BodyReadFailure.TooLarge))
                else readNodeStream(request, limit, once)
      catch case error: Throwable => once(Left(BodyReadFailure.Failed(error)))
    end read

    /** Decodes UTF-8 like Node's `Buffer.toString`: invalid sequences become U+FFFD; a BOM is dropped. */
    def decodeUtf8(bytes: Uint8Array): String =
      js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)("utf-8").decode(bytes).asInstanceOf[String]

    private def encodeUtf8(text: String): Uint8Array =
      js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)().encode(text).asInstanceOf[Uint8Array]

    private final class Once(done: Outcome => Unit):
        private var completed = false
        def apply(outcome: Outcome): Unit =
          if !completed then
              completed = true
              done(outcome)

    private def prebuffered(event: js.Dynamic, request: js.Dynamic): Option[js.Any] =
      val web = event.web
      val webBody: js.Any =
        if Nitro.isObject(web) && Nitro.isObject(web.request) then web.request.body else js.undefined
      val candidates: List[js.Any] = List(
        event._requestBody,
        webBody,
        request.asInstanceOf[WithRawBody].h3RawBody,
        request.rawBody,
        request.body,
      )
      candidates.find(truthy)

    private def truthy(value: js.Any): Boolean =
      !js.isUndefined(value) && value != null && !(js.typeOf(value) == "string" && value.asInstanceOf[String].isEmpty) &&
        !(js.typeOf(value) == "boolean" && !value.asInstanceOf[Boolean])

    private def contentLength(headers: js.Dictionary[js.Any]): Option[Long] =
      headers.get("content-length").flatMap { value =>
        if js.typeOf(value) == "string" then value.asInstanceOf[String].trim.toLongOption else None
      }

    private def isChunked(headers: js.Dictionary[js.Any]): Boolean =
      headers.get("transfer-encoding").exists { value =>
        js.typeOf(value) == "string" &&
        value.asInstanceOf[String].split(',').map(_.trim.toLowerCase).contains("chunked")
      }

    private def fromValue(value: js.Any, limit: Long, done: Once): Unit =
      try
          val dynamic = value.asInstanceOf[js.Dynamic]
          if js.typeOf(value) == "string" then bounded(encodeUtf8(value.asInstanceOf[String]), limit, done)
          else if isInstance(value, "Uint8Array") then bounded(value.asInstanceOf[Uint8Array], limit, done)
          else if isInstance(value, "ArrayBuffer") then
            bounded(new Uint8Array(value.asInstanceOf[ArrayBuffer]), limit, done)
          else if Nitro.isObject(value) && Nitro.isFunction(dynamic.getReader) then readWebStream(dynamic, limit, done)
          else if Nitro.isObject(value) && Nitro.isFunction(dynamic.on) then readNodeStream(dynamic, limit, done)
          else if isInstance(value, "URLSearchParams") then bounded(encodeUtf8(dynamic.toString().asInstanceOf[String]), limit, done)
          else if Nitro.isObject(value) && dynamic.constructor == js.Dynamic.global.Object then
            bounded(encodeUtf8(js.JSON.stringify(value)), limit, done)
          else done(Left(BodyReadFailure.Failed(IllegalStateException("Unsupported pre-read request body"))))
      catch case error: Throwable => done(Left(BodyReadFailure.Failed(error)))

    private def bounded(bytes: Uint8Array, limit: Long, done: Once): Unit =
      if bytes.byteLength > limit then done(Left(BodyReadFailure.TooLarge)) else done(Right(Some(bytes)))

    private def readNodeStream(stream: js.Dynamic, limit: Long, done: Once): Unit =
      // A stream that already ended (or was destroyed) never emits again: other code on this event
      // consumed it without a cache h34s knows. Fail now instead of waiting for the client.
      // `complete` is not checked: Node sets it when the body has arrived, before anyone reads it.
      if Nitro.isTrue(stream.readableEnded) || Nitro.isTrue(stream.destroyed) then
          done(Left(BodyReadFailure.Failed(IllegalStateException("The request body was already read"))))
      else readNodeStreamListeners(stream, limit, done)

    private def readNodeStreamListeners(stream: js.Dynamic, limit: Long, done: Once): Unit =
      val chunks   = js.Array[Uint8Array]()
      var total    = 0L
      var finished = false
      lazy val onData: js.Function1[js.Any, Unit] = chunk =>
        if !finished then
            val bytes = if js.typeOf(chunk) == "string" then encodeUtf8(chunk.asInstanceOf[String]) else chunk.asInstanceOf[Uint8Array]
            total += bytes.byteLength
            if total > limit then finish(Left(BodyReadFailure.TooLarge))
            else chunks.push(bytes)
      lazy val onEnd: js.Function0[Unit] = () => finish(Right(Some(concat(chunks, total))))
      lazy val onError: js.Function1[js.Any, Unit] = error => finish(Left(BodyReadFailure.Failed(throwable(error))))
      lazy val onClose: js.Function0[Unit] = () =>
        finish(Left(BodyReadFailure.Failed(IllegalStateException("The request body closed before it ended"))))
      def finish(outcome: Outcome): Unit =
        if !finished then
            finished = true
            stream.removeListener("data", onData)
            stream.removeListener("end", onEnd)
            stream.removeListener("error", onError)
            stream.removeListener("close", onClose)
            // Unread data keeps flowing and is discarded; a late transport error must not crash Node.
            stream.on("error", (((_: js.Any) => ()): js.Function1[js.Any, Unit]))
            done(outcome)
      stream.on("data", onData)
      stream.on("end", onEnd)
      stream.on("error", onError)
      stream.on("close", onClose)
    end readNodeStreamListeners

    private def readWebStream(stream: js.Dynamic, limit: Long, done: Once): Unit =
      val reader = stream.getReader()
      val chunks = js.Array[Uint8Array]()
      var total  = 0L
      def next(): Unit =
        reader.read().asInstanceOf[js.Promise[js.Dynamic]].`then`[Unit](
          (step: js.Dynamic) =>
            if step.done.asInstanceOf[Boolean] then done(Right(Some(concat(chunks, total))))
            else
                val bytes = step.value.asInstanceOf[Uint8Array]
                total += bytes.byteLength
                if total > limit then
                    reader.cancel()
                    done(Left(BodyReadFailure.TooLarge))
                else
                    chunks.push(bytes)
                    next()
          ,
          (error: Any) => done(Left(BodyReadFailure.Failed(throwable(error)))),
        )
        ()
      next()

    private def concat(chunks: js.Array[Uint8Array], total: Long): Uint8Array =
      val result = new Uint8Array(total.toInt)
      var offset = 0
      chunks.foreach { chunk =>
        result.set(chunk, offset)
        offset += chunk.length
      }
      result

    private def isInstance(value: js.Any, constructor: String): Boolean =
      val native = js.Dynamic.global.globalThis.selectDynamic(constructor)
      !js.isUndefined(native) && js.special.instanceof(value, native)

    def throwable(error: Any): Throwable = error.asInstanceOf[Matchable] match
        case throwable: Throwable => throwable
        case other                => js.JavaScriptException(other)
end BodyReader
