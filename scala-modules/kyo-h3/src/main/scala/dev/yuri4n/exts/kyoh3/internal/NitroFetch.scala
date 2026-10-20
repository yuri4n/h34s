package dev.yuri4n.exts.kyoh3.internal

import dev.yuri4n.exts.h3.internal.{H3Event, Nitro}
import dev.yuri4n.exts.kyoh3.{Fetch, FetchError, FetchRequest, FetchResponse}
import kyo.{Abort, AllowUnsafe, Async, Chunk, Emit, Frame, Kyo, Loop, Scope, Stream, Sync, `<`}

import scala.scalajs.js
import scala.util.control.NonFatal

// claude 2026-10-19: created: the default Fetch, over Nitro's event.fetch (fetchWithEvent over localFetch), captured at request start
/** The server-side `$fetch` of one request: Nitro's `event.fetch` for in-process paths, and the
  * global `fetch` for absolute URLs.
  *
  * Nitro 2.13.1 sets `event.fetch` in its h3 `onRequest`, before any route handler:
  * `fetchWithEvent(event, req, init, { fetch: localFetch })`. It forwards the incoming headers
  * (cookies and authorization included) and answers a path that starts with `/` in-process through
  * node-mock-http. Unlike `event.$fetch` (ofetch), it does not buffer, retry or throw on an error
  * status, so the head arrives as soon as the callee's handler settles.
  *
  * Two additions make it behave like Nuxt's `$fetch` without its leaks (ADR-0005, decision 1):
  * an in-process path gets the app's `app.baseURL` prefix, where Nitro mounts its routes, and the
  * call passes a context with only the caller's `_platform` and `waitUntil`. Without it,
  * `fetchWithEvent` passes the caller's whole `event.context`, which the callee can read as
  * `event.node.req.__unenv__`.
  *
  * For any other URL, `localFetch` calls `globalThis.fetch`, but `fetchWithEvent` has already added
  * the incoming headers, so the user's session would reach that host. h34s therefore calls
  * `globalThis.fetch` itself for such a URL, with only the headers of the `FetchRequest` (ADR-0005,
  * Q5 option B).
  */
private[exts] final class NitroFetch(event: H3Event) extends Fetch:
    private val local: js.Any = event.asInstanceOf[js.Dynamic].fetch

    // claude 2026-10-20: changed: only an in-process path goes through event.fetch, with the base URL and the minimal context; an absolute URL goes through the global fetch without the incoming headers (ADR-0005, decision 1 and Q5 option B)
    def raw(request: FetchRequest)(using Frame): FetchResponse < (Async & Abort[FetchError] & Scope) =
      val inProcess = NitroFetch.isInProcess(request.url)
      if inProcess && !Nitro.isFunction(local) then
          Abort.panic(IllegalStateException("Server-side $fetch needs Nitro: this h3 event has no fetch function"))
      else
          Sync.Unsafe.defer {
            val call =
              if inProcess then
                  new NitroFetch.Call(
                    local.asInstanceOf[js.Function2[String, js.Any, js.Promise[js.Dynamic]]],
                    NitroFetch.withBase(request.url, NitroFetch.baseURL),
                    request,
                    Some(NitroFetch.minimalContext(event)),
                  )
              else new NitroFetch.Call(NitroFetch.global, request.url, request, None)
            // Registered before the wait: a caller that is interrupted before the head arrives still
            // cancels the body when it arrives.
            Scope.ensure(call.release).andThen(call.head)
          }
end NitroFetch

private[exts] object NitroFetch:
    // claude 2026-10-20: created: the in-process test that decides header forwarding (ADR-0005, Q5 option B)
    /** A URL that starts with `/` is answered in-process, as Nitro's `localFetch` decides. Only such
      * a call gets the incoming request's headers.
      */
    private[internal] def isInProcess(url: String): Boolean = url.startsWith("/")

    // claude 2026-10-20: created: the app.baseURL prefix of an in-process path, as Nuxt's $fetch applies it (ADR-0005, decision 1)
    /** Prefixes an in-process path with the app's base URL, as Nuxt's own `$fetch` does (ofetch
      * with ufo 1.6's `withBase`): a base of `/` or `""` changes nothing, a path that already starts
      * with the base (followed by its end, `/` or `?`) stays as it is, and otherwise the base comes
      * first, with one slash at the join. A base without a leading slash gets one, so the result
      * stays in-process.
      */
    private[exts] def withBase(path: String, base: String): String =
      val trimmed = base.reverse.dropWhile(_ == '/').reverse
      if trimmed.isEmpty then path
      else
          val prefix  = if trimmed.startsWith("/") then trimmed else s"/$trimmed"
          val already =
            path.startsWith(prefix) && (path.length == prefix.length || path.charAt(prefix.length) == '/' ||
              path.charAt(prefix.length) == '?')
          if already then path else prefix + path

    // claude 2026-10-20: created: app.baseURL from the plugin's startup runtime config, the copy Nitro mounts its router with (ADR-0005, decision 1)
    /** `app.baseURL` of Nitro's startup runtime config, read through the h34s Nitro plugin: the copy
      * that Nitro mounts its routes under (`h3App.use(config.app.baseURL, router.handler)`). `/`
      * without the plugin or without a string value.
      */
    private[internal] def baseURL: String =
      Nitro.global.flatMap { record =>
        try
            val config = record.runtimeConfig().asInstanceOf[js.Dynamic]
            val base   = if Nitro.isObject(config) && Nitro.isObject(config.app) then config.app.baseURL else js.undefined
            Option.when(js.typeOf(base) == "string")(base.asInstanceOf[String])
        catch case NonFatal(_) => None
      }.getOrElse("/")

    // claude 2026-10-20: created: the context of an in-process call, only _platform and waitUntil (ADR-0005, decision 1)
    /** The keys of the caller's `event.context` that an in-process call passes on. Nitro's
      * `onRequest` reads only these from the context of a local request (it spreads `_platform` into
      * the callee's context and copies `waitUntil`); route rules, middleware and plugins read the
      * callee's own context.
      */
    private[exts] val contextKeys: List[String] = List("_platform", "waitUntil")

    /** A new object with the caller's `contextKeys` that are set. Always an object: `fetchWithEvent`
      * passes the caller's whole `event.context` when `init.context` is missing.
      */
    private def minimalContext(event: H3Event): js.Object =
      val context = js.Dictionary.empty[js.Any]
      val caller  = event.asInstanceOf[js.Dynamic].context
      if Nitro.isObject(caller) then
          contextKeys.foreach { key =>
            val value = caller.selectDynamic(key)
            if !js.isUndefined(value) then context(key) = value
          }
      context.asInstanceOf[js.Object]

    // claude 2026-10-20: created: the global fetch for absolute URLs, looked up at each call as Nitro's localFetch does (ADR-0005, Q5 option B)
    /** `globalThis.fetch`, called as a method of `globalThis`. It sees only the request's own
      * headers.
      */
    private val global: js.Function2[String, js.Any, js.Promise[js.Dynamic]] =
      (url, init) => js.Dynamic.global.globalThis.fetch(url, init).asInstanceOf[js.Promise[js.Dynamic]]

    // claude 2026-10-19: created: the entity headers that fetchWithEvent forwards from the incoming request
    /** `fetchWithEvent` forwards the incoming request's `content-type` and `content-length` even
      * when this request has another body or none. An empty value overrides them; node-mock-http
      * drops empty header values, so the callee sees only what this request sends.
      */
    private val entityHeaders: List[String] = List("content-type", "content-length")

    // claude 2026-10-20: changed: the call takes the URL to send (an in-process path carries the base URL) and the in-process context (ADR-0005, decision 1)
    /** One request. `url` is what goes to `fetch`; `context`, for an in-process call, replaces the
      * caller's `event.context` that `fetchWithEvent` would pass.
      */
    private[internal] final class Call(
        fetch: js.Function2[String, js.Any, js.Promise[js.Dynamic]],
        url: String,
        request: FetchRequest,
        context: Option[js.Object],
    )(using AllowUnsafe):
        private var response: js.Dynamic = null
        private var reader: js.Dynamic   = null
        private var finished             = false

        private val pending: js.Promise[js.Dynamic] =
          try fetch(url, init)
          catch case error: Throwable => js.Promise.reject(error)

        // claude 2026-10-19: changed: every derived promise handles a rejection (a network error must not become an unhandled rejection)
        pending.`then`[Unit]((arrived: js.Dynamic) => { response = arrived; () }, ignore)

        // claude 2026-10-20: changed: the in-process call carries the minimal context; an absolute URL gets only these headers (ADR-0005, decision 1 and Q5 option B)
        private def init: js.Any =
          val headers = js.Dictionary.empty[String]
          if context.isDefined then entityHeaders.foreach(name => headers(name) = "")
          request.body.foreach { body =>
            headers("content-type") = body.contentType
            headers("content-length") = utf8Length(body.text).toString
          }
          // A plain object: fetchWithEvent spreads init.headers, which drops a Headers instance.
          request.headers.foreach((name, value) => headers(name.toLowerCase) = value)
          val init = js.Dynamic.literal(method = request.method, headers = headers)
          request.body.foreach(body => init.body = body.text)
          context.foreach(value => init.context = value)
          init

        // claude 2026-10-20: changed: a Plain body that breaks after the head is FetchError.BodyFailed (ADR-0005, Q14)
        def head(using Frame): FetchResponse < (Async & Abort[FetchError]) =
          Abort.run[Throwable](Interop.await(pending)).map(_.foldError(
            arrived =>
              Sync.defer {
                val status  = arrived.status.asInstanceOf[Int]
                val headers = headersOf(arrived)
                if FetchResponse.isStreamed(headers) then Kyo.lift(FetchResponse.Streamed(status, headers, body(status)))
                else
                    Abort.run[Throwable](Interop.await(arrived.text().asInstanceOf[js.Promise[String]])).map(_.foldError(
                      text =>
                        finished = true
                        FetchResponse.Plain(status, headers, text)
                      ,
                      error => Abort.fail(bodyFailed(status, error.failureOrPanic)),
                    ))
              },
            error => Abort.fail(FetchError.Failed(message(error.failureOrPanic))),
          ))

        // claude 2026-10-20: changed: a read that fails is FetchError.BodyFailed, not a panic (ADR-0005, Q14); one body start for bytes and strings removes one leading byte order mark from the whole body (ADR-0007, review 4329965909)
        /** The body as text chunks, read once. Each read is one pull of the callee's stream. A read
          * that fails (the callee's stream failed after its head, or the connection closed) fails
          * the stream with `FetchError.BodyFailed`.
          *
          * The text is the body after one UTF-8 decode, which removes one leading byte order mark
          * (U+FEFF). Bytes go through a `TextDecoder` that keeps every U+FEFF (`ignoreBOM`). A
          * JavaScript route can answer in-process with strings, or with strings and bytes mixed,
          * and strings skip the decoder. So h34s removes the mark here, once: one leading U+FEFF of
          * the first text of the body, whichever kind of chunk gives it. A U+FEFF after the start
          * stays, as in one UTF-8 decode of the whole body.
          */
        private def body(status: Int)(using Frame): Stream[String, Async & Scope & Abort[FetchError]] =
          Stream[String, Async & Abort[FetchError]] {
            Sync.defer {
              val native = response.body
              if native == null || js.isUndefined(native) then
                  finished = true
                  Kyo.unit
              else if reader != null then
                  Abort.panic(IllegalStateException("A streamed $fetch body can be read only once"))
              else
                  reader = native.getReader()
                  val decoder = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)("utf-8", js.Dynamic.literal(ignoreBOM = true))
                  // One body start for both kinds of chunk: the first text, bytes or string, loses one U+FEFF.
                  var started = false
                  def fromStart(text: String): String =
                    if started || text.isEmpty then text
                    else
                        started = true
                        if text.startsWith("\uFEFF") then text.substring(1) else text
                  Loop.foreach {
                    readStep(status, reader.read().asInstanceOf[js.Promise[js.Dynamic]]).map { step =>
                      if step.done.asInstanceOf[Boolean] then
                          finished = true
                          val tail = fromStart(decoder.decode().asInstanceOf[String])
                          if tail.isEmpty then Loop.done else Emit.valueWith(Chunk(tail))(Loop.done)
                      else
                          val value = step.value
                          val text  = fromStart(
                            if js.typeOf(value) == "string" then value.asInstanceOf[String]
                            else decoder.decode(value, js.Dynamic.literal(stream = true)).asInstanceOf[String],
                          )
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
                pending.`then`[Unit]((arrived: js.Dynamic) => { cancel(arrived, null).`then`[Unit]((_: Unit) => (), ignore); () }, ignore)
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

    private val ignore: js.Function1[Any, Unit | js.Thenable[Unit]] = _ => ()

    // claude 2026-10-20: changed: repeated names are joined: Headers.forEach gives each set-cookie value apart, and only the last one was kept (ADR-0005, Q14)
    /** Lower-case names. `Headers.forEach` gives every `set-cookie` value apart and every other
      * header already joined, so a repeated name is joined here with `", "`.
      */
    private def headersOf(response: js.Dynamic): Map[String, String] =
      val collected = scala.collection.mutable.LinkedHashMap.empty[String, String]
      response.headers.forEach(((value: String, name: String) =>
        val key = name.toLowerCase
        collected(key) = collected.get(key).fold(value)(earlier => s"$earlier, $value")
      ): js.Function2[String, String, Unit])
      collected.toMap

    // claude 2026-10-20: created: a body that breaks after the head: h34s' own reason, the cause for the server log (ADR-0005, Q14)
    private def bodyFailed(status: Int, cause: Throwable): FetchError =
      val error = FetchError.BodyFailed(status, "the body ended with an error before its end")
      error.initCause(cause)
      error

    private def utf8Length(text: String): Int =
      js.Dynamic.newInstance(js.Dynamic.global.TextEncoder)().encode(text).length.asInstanceOf[Int]

    private def message(error: Throwable): String =
      Option(error.getMessage).getOrElse(error.getClass.getName)

    // claude 2026-10-20: changed: a failed read is FetchError.BodyFailed (was a panic, awaitOrPanic) (ADR-0005, Q14)
    private def readStep(status: Int, promise: => js.Promise[js.Dynamic])(using Frame): js.Dynamic < (Async & Abort[FetchError]) =
      Abort.run[Throwable](Interop.await(promise)).map(_.foldError(value => value, error => Abort.fail(bodyFailed(status, error.failureOrPanic))))
end NitroFetch
