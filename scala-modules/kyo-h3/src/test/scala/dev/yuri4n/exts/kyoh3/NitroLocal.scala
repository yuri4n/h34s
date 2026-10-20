package dev.yuri4n.exts.kyoh3

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("node:module", JSImport.Namespace)
private object NodeModule extends js.Object:
    def createRequire(path: String): js.Dynamic = js.native

@js.native
@JSImport("node:http", JSImport.Namespace)
private object NitroNodeHttp extends js.Object:
    def createServer(listener: js.Any): js.Dynamic = js.native

// claude 2026-10-19: created: a copy of nitropack 2.13.1's createNitroApp wiring (dist/runtime/internal/app.mjs:41-115), built from nitropack's own h3, node-mock-http and ofetch
/** The in-process local fetch of Nitro 2.13.1, for tests.
  *
  * It loads h3, node-mock-http and ofetch exactly as nitropack resolves them in the pnpm
  * workspace (h3 1.15.5, node-mock-http 1.0.4, ofetch 1.5.1; their CommonJS builds), and copies
  * the wiring of `nitropack/dist/runtime/internal/app.mjs`: `onRequest` sets `event.fetch` and
  * `event.$fetch` with `fetchWithEvent` (:47-64), a preemptive router (:92-94), `localFetch`
  * through node-mock-http for paths that start with `/` (:100-109) with `normalizeFetchResponse`
  * (`utils.mjs:45-54`), and ofetch's `$fetch` with `baseURL: "/"` (:110-115). It leaves out route
  * rules, plugins other than the h34s request record, async context and the error handlers.
  *
  * Handlers are the h34s handlers under test; they import h3 from the h3 test fixture (1.15.4),
  * like an application whose own h3 differs from nitropack's.
  */
// claude 2026-10-20: changed: private[exts], so the ZIO adapter's NitroLocalFetchSpec runs on the same harness
private[exts] object NitroLocal:
    private def fail(message: String): Nothing = throw IllegalStateException(message)

    /** `require` as nitropack 2.13.1 resolves its dependencies. */
    lazy val requireFromNitro: js.Dynamic =
      val workspace = js.Dynamic.global.process.env.H34S_WORKSPACE
      if js.isUndefined(workspace) then fail("H34S_WORKSPACE is not set: run the tests through sbt (build.sbt kyoH3Ext)")
      val fromWorkspace = NodeModule.createRequire(s"$workspace/package.json")
      val nuxt          =
        try fromWorkspace.resolve("nuxt/package.json").asInstanceOf[String]
        catch case _: Throwable => fail("nuxt is not installed in the workspace: run pnpm install --frozen-lockfile")
      val nitropack = NodeModule.createRequire(nuxt).resolve("nitropack/package.json").asInstanceOf[String]
      NodeModule.createRequire(nitropack)

    lazy val h3: js.Dynamic             = requireFromNitro("h3")
    lazy val nodeMockHttp: js.Dynamic   = requireFromNitro("node-mock-http")
    lazy val ofetch: js.Dynamic         = requireFromNitro("ofetch")

    /** Package versions as nitropack resolves them, for the report. */
    def versions: Map[String, String] =
      List("h3", "node-mock-http", "ofetch").map { name =>
        val main = requireFromNitro.resolve(name).asInstanceOf[String]
        val root = main.substring(0, main.lastIndexOf(s"/node_modules/$name/") + s"/node_modules/$name/".length)
        name -> NodeModule.createRequire(main)(s"${root}package.json").version.asInstanceOf[String]
      }.toMap

    // claude 2026-10-20: changed: baseURL mounts the router as Nitro does (app.mjs:138); a local request's _platform and waitUntil reach the context as in app.mjs:48-60 (ADR-0005, decision 1)
    /** One Nitro app. `baseURL` is `app.baseURL`: the router is mounted under it, as Nitro mounts its
      * routes (`h3App.use(config.app.baseURL, router.handler)`). h34s reads the base URL from the
      * plugin's startup record, so a test with another base also installs it with `withBaseURL`.
      */
    final class App(development: Boolean = false, baseURL: String = "/"):
        private var onRequestHook: js.Dynamic => Unit = _ => ()

        /** Runs after Nitro's own `onRequest` work, like a Nitro `request` hook. */
        def onRequest(hook: js.Dynamic => Unit): Unit = onRequestHook = hook

        private lazy val app: js.Dynamic = h3.createApp(js.Dynamic.literal(
          onRequest = ((event: js.Dynamic) => {
            if js.isUndefined(event.context.nitro) then event.context.nitro = js.Dynamic.literal(errors = js.Array())
            // nitropack app.mjs:49-60: a local request's context gives only _platform (spread into
            // the context) and waitUntil.
            val fetchContext = event.node.req.__unenv__
            if fetchContext != null && js.typeOf(fetchContext) == "object" then
                val platform = fetchContext._platform
                if !js.isUndefined(platform) && platform != null then
                    event.context = js.Object.assign(js.Dynamic.literal(_platform = platform), platform.asInstanceOf[js.Object], event.context.asInstanceOf[js.Object])
                if js.isUndefined(event.context.waitUntil) && !js.isUndefined(fetchContext.waitUntil) then
                    event.context.waitUntil = fetchContext.waitUntil
            event.fetch = ((req: js.Any, init: js.Any) =>
              h3.fetchWithEvent(event, req, init, js.Dynamic.literal(fetch = localFetch))
            ): js.Function2[js.Any, js.Any, js.Any]
            event.$fetch = ((req: js.Any, init: js.Any) =>
              h3.fetchWithEvent(event, req, init, js.Dynamic.literal(fetch = globalFetch))
            ): js.Function2[js.Any, js.Any, js.Any]
            // The h34s Nitro plugin's request hook (packages/nuxt/src/runtime/context.mjs:37-47).
            event.context.h34s = js.Dynamic.literal(version = 1, development = development, debug = false)
            onRequestHook(event)
          }): js.Function1[js.Dynamic, Unit],
        ))

        private lazy val router: js.Dynamic = h3.createRouter(js.Dynamic.literal(preemptive = true))
        private lazy val nodeHandler: js.Dynamic =
          app.use(baseURL, router.handler)
          h3.toNodeListener(app)

        /** Nitro's `localFetch`: in-process for paths that start with `/`, else `globalThis.fetch`. */
        lazy val localFetch: js.Function2[js.Any, js.Any, js.Promise[js.Dynamic]] = (input, init) =>
          if !input.toString.startsWith("/") then
              js.Dynamic.global.fetch(input, init).asInstanceOf[js.Promise[js.Dynamic]]
          else
              nodeMockHttp.fetchNodeRequestHandler(nodeHandler, input, init).asInstanceOf[js.Promise[js.Dynamic]]
                .`then`[js.Dynamic]((response: js.Dynamic) => normalizeFetchResponse(response))

        /** Nitro's global `$fetch` (`globalThis.$fetch`): ofetch over `localFetch`. */
        lazy val globalFetch: js.Dynamic =
          ofetch.createFetch(js.Dynamic.literal(
            fetch = localFetch,
            Headers = ofetch.Headers,
            defaults = js.Dynamic.literal(baseURL = "/"),
          ))

        def route(path: String, handler: EventHandler, method: Option[String] = None): App =
          method.fold(router.use(path, handler.toNative))(router.use(path, handler.toNative, _))
          this

        // claude 2026-10-20: created: a JavaScript route, built with nitropack's own h3 (the minimal context test)
        /** A JavaScript route: `handler` receives the h3 event, as a handler in `server/api` does. */
        def routeNative(path: String, handler: js.Function1[js.Dynamic, js.Any]): App =
          router.use(path, h3.defineEventHandler(handler))
          this

        /** An in-process request from outside any handler, as `localFetch("/path", init)`. */
        def fetch(path: String, init: js.Dynamic = js.Dynamic.literal()): Future[js.Dynamic] =
          localFetch(path, init).toFuture

        /** A real node:http server on 127.0.0.1 with the same handler, for comparisons over a socket. */
        def listen(): Future[Server] =
          val server    = NitroNodeHttp.createServer(nodeHandler)
          val listening = Promise[Server]()
          server.once("error", ((error: js.Any) => { listening.tryFailure(js.JavaScriptException(error)); () }): js.Function1[js.Any, Unit])
          server.listen(0, "127.0.0.1", (() => {
            listening.success(Server(server, server.address().port.asInstanceOf[Int]))
            ()
          }): js.Function0[Unit])
          listening.future
    end App

    // claude 2026-10-20: created: the plugin's startup record with app.baseURL, for the base URL tests (ADR-0005, decision 1)
    /** Runs `body` with an h34s startup record whose runtime config has `app.baseURL = baseURL`, as the
      * h34s Nitro plugin installs it, and removes the record when `body` completes.
      */
    def withBaseURL[A](baseURL: String)(body: => Future[A])(using ExecutionContext): Future[A] =
      installBaseURL(baseURL)
      val result =
        try body
        catch case error: Throwable => Future.failed(error)
      result.transform { outcome =>
        NitroGlobal.remove()
        outcome
      }

    /** Installs the startup record of `withBaseURL`; `NitroGlobal.remove()` removes it. */
    def installBaseURL(baseURL: String): Unit =
      val config: js.Function0[js.Any] = () => js.Dynamic.literal(app = js.Dynamic.literal(baseURL = baseURL))
      NitroGlobal.install(js.Dynamic.literal(version = 1, development = false, debug = false, runtimeConfig = config))

    final case class Server(native: js.Dynamic, port: Int):
        def url(path: String): String = s"http://127.0.0.1:$port$path"
        def close(): Future[Unit] =
          val closed = Promise[Unit]()
          native.close((() => { closed.trySuccess(()); () }): js.Function0[Unit])
          native.closeAllConnections()
          closed.future

    /** nitropack `utils.mjs:45-70`: splits `set-cookie` values; other responses pass unchanged. */
    private def normalizeFetchResponse(response: js.Dynamic): js.Dynamic =
      if !response.headers.has("set-cookie").asInstanceOf[Boolean] then response
      else
          val headers = js.Dynamic.newInstance(js.Dynamic.global.Headers)()
          response.headers.forEach(((value: String, name: String) =>
            if name == "set-cookie" then
                h3.splitCookiesString(value).asInstanceOf[js.Array[String]].foreach(cookie => headers.append("set-cookie", cookie))
            else headers.set(name, value)
            ()
          ): js.Function2[String, String, Unit])
          js.Dynamic.newInstance(js.Dynamic.global.Response)(
            response.body,
            js.Dynamic.literal(status = response.status, statusText = response.statusText, headers = headers),
          )
end NitroLocal
