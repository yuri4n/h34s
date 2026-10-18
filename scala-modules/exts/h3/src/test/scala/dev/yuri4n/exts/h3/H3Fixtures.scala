package dev.yuri4n.exts.h3

import dev.yuri4n.exts.h3.internal.H3Event

import scala.collection.mutable
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Real h3 events over a fake Node request and response, shared by the h3 and Kyo suites. */
private[exts] object H3Fixtures:
    @js.native
    @JSImport("h3", "H3Event")
    private object NativeH3Event extends js.Object

    @js.native
    @JSImport("node:stream", "PassThrough")
    private object PassThrough extends js.Object

    /** A Node-like request stream. Write body chunks with `send`, then `finish`. */
    final class FakeRequest(
        method: String = "GET",
        url: String = "/test",
        headers: Seq[(String, String)] = Nil,
    ):
        val native: js.Dynamic = js.Dynamic.newInstance(PassThrough.asInstanceOf[js.Dynamic])()
        native.method = method
        native.url = url
        native.rawHeaders = js.Array(headers.flatMap((name, value) => Seq(name, value))*)
        private val joined = js.Dictionary.empty[js.Any]
        headers.foreach { (name, value) =>
          val key = name.toLowerCase
          joined(key) = joined.get(key).fold[js.Any](value)(existing => s"$existing, $value")
        }
        native.headers = joined

        def send(chunk: String): Unit = native.write(chunk)
        def finish(): Unit            = native.end()

        /** Active listeners for an event, to check that readers clean up. */
        def listeners(name: String): Int = native.listenerCount(name).asInstanceOf[Int]

    /** A Node-like `ServerResponse` that records status, headers, writes and lifecycle calls. */
    final class FakeResponse(withSocket: Boolean = true):
        private var listener: Option[js.Function0[Unit]] = None
        private val values = mutable.LinkedHashMap.empty[String, js.Any]
        var writes = 0
        var ended = false
        val chunks = mutable.ListBuffer.empty[String]
        val firstWrite = scala.concurrent.Promise[Unit]()
        /** Status and header names when the first chunk was written. */
        var headAtFirstWrite: Option[(Int, List[String])] = None

        val native: js.Dynamic = js.Dynamic.literal(
          statusCode = 200, statusMessage = "OK", headersSent = false,
          writableEnded = false, destroyed = false, closed = false,
          socket = if withSocket then js.Dynamic.literal() else js.undefined,
        )
        private val register: js.Function2[String, js.Function0[Unit], js.Dynamic] = (name, callback) =>
          if name == "close" then listener = Some(callback)
          native
        native.updateDynamic("once")(register)
        native.updateDynamic("on")(register)
        native.updateDynamic("removeListener")(((name: String, callback: js.Function0[Unit]) => {
          if name == "close" && listener.contains(callback) then listener = None
          native
        }): js.Function2[String, js.Function0[Unit], js.Dynamic])
        native.updateDynamic("setHeader")(((name: String, value: js.Any) => {
          values(name.toLowerCase) = value
          native
        }): js.Function2[String, js.Any, js.Dynamic])
        native.updateDynamic("getHeader")(((name: String) => values.getOrElse(name.toLowerCase, js.undefined)): js.Function1[String, js.Any])
        native.updateDynamic("hasHeader")(((name: String) => values.contains(name.toLowerCase)): js.Function1[String, Boolean])
        native.updateDynamic("removeHeader")(((name: String) => { values.remove(name.toLowerCase); () }): js.Function1[String, Unit])
        native.updateDynamic("getHeaderNames")((() => js.Array(values.keys.toSeq*)): js.Function0[js.Array[String]])
        native.updateDynamic("appendHeader")(((name: String, value: js.Any) => {
          val key = name.toLowerCase
          values(key) = values.get(key) match
              case None                                    => value
              case Some(existing) if js.Array.isArray(existing) =>
                existing.asInstanceOf[js.Array[js.Any]].concat(js.Array(value))
              case Some(existing)                          => js.Array(existing, value)
          native
        }): js.Function2[String, js.Any, js.Dynamic])
        native.updateDynamic("write")(((chunk: js.Any) => {
          if headAtFirstWrite.isEmpty then headAtFirstWrite = Some(status -> headerNames)
          chunks += String.valueOf(chunk)
          native.headersSent = true
          firstWrite.trySuccess(())
          true
        }): js.Function1[js.Any, Boolean])
        native.updateDynamic("end")(((_: js.Any) => {
          writes += 1
          ended = true
          native.writableEnded = true
          native.headersSent = true
          ()
        }): js.Function1[js.Any, Unit])
        native.updateDynamic("destroy")((() => disconnect()): js.Function0[Unit])

        def status: Int           = native.statusCode.asInstanceOf[Int]
        def hasListener: Boolean  = listener.nonEmpty
        def destroyed: Boolean    = native.destroyed.asInstanceOf[Boolean]
        def header(name: String): Option[String] =
          values.get(name.toLowerCase).map { value =>
            if js.Array.isArray(value) then value.asInstanceOf[js.Array[js.Any]].mkString(", ") else String.valueOf(value)
          }
        def headerValues(name: String): List[String] =
          values.get(name.toLowerCase).toList.flatMap { value =>
            if js.Array.isArray(value) then value.asInstanceOf[js.Array[js.Any]].toList.map(String.valueOf)
            else List(String.valueOf(value))
          }
        def headerNames: List[String] = values.keys.toList

        def disconnect(): Unit =
          native.destroyed = true
          native.closed = true
          listener.foreach(callback => callback())
    end FakeResponse

    /** A real h3 `H3Event`, optionally with the h34s Nitro request record. */
    def event(request: FakeRequest, response: FakeResponse, context: Option[js.Object] = None): H3Event =
      val event = js.Dynamic.newInstance(NativeH3Event.asInstanceOf[js.Dynamic])(request.native, response.native)
        .asInstanceOf[H3Event]
      context.foreach(record => event.context("h34s") = record)
      event

    /** The Nitro plugin's per-request record with its `development` and `debug` flags. */
    def development(enabled: js.Any, debug: js.Any = js.undefined): js.Object =
      js.Dynamic.literal(version = 1, development = enabled, debug = debug)
end H3Fixtures
