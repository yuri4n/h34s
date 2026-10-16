package dev.yuri4n.exts.nuxthub

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** The same construction emitted by NuxtHub 0.10.8 for its memory driver. */
object KvTestFixtures:
    @js.native
    @JSImport("unstorage", "createStorage")
    private def createStorage(options: js.Object): HubKv = js.native

    @js.native
    @JSImport("unstorage/drivers/memory", "default")
    private def memoryDriver(options: js.Object): js.Object = js.native

    def memory(): HubKv =
      createStorage(js.Dynamic.literal(driver = memoryDriver(js.Dynamic.literal())))

    /** Fault injection and exact-call assertions; no substitute storage implementation. */
    final class Recording:
        var calls = List.empty[(String, String)]
        var result: js.Any = null
        var stored: js.Any = js.undefined
        var options: js.UndefOr[NativeKvSetOptions] = js.undefined
        var failure: Option[Throwable] = None
        var reject = false

        private def invoke[A](name: String, key: String, value: => A): js.Promise[A] =
          calls = calls :+ (name -> key)
          failure match
              case Some(error) if reject => js.Promise.reject(error)
              case Some(error)           => throw error
              case None                  => js.Promise.resolve(value)

        val native: HubKv = js.Dynamic.literal(
          get = ((key: String) => invoke("get", key, result)): js.Function1[String, js.Promise[js.Any]],
          set = ((key: String, value: js.Any, opts: js.UndefOr[NativeKvSetOptions]) => {
            stored = value
            options = opts
            invoke("set", key, ())
          }): js.Function3[String, js.Any, js.UndefOr[NativeKvSetOptions], js.Promise[Unit]],
          has = ((key: String) => invoke("has", key, true)): js.Function1[String, js.Promise[Boolean]],
          del = ((key: String) => invoke("del", key, ())): js.Function1[String, js.Promise[Unit]],
          keys = ((base: js.UndefOr[String]) => invoke("keys", base.getOrElse(""), js.Array("scope:item"))): js.Function1[js.UndefOr[String], js.Promise[js.Array[String]]],
        ).asInstanceOf[HubKv]
