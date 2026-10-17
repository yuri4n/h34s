package dev.yuri4n.exts.zionuxthub

import dev.yuri4n.exts.h3.ConfigError
import dev.yuri4n.exts.h3.internal.NativeStorage
import dev.yuri4n.exts.kyoh3.NitroGlobal
import dev.yuri4n.exts.kyonuxthub.KvTestFixtures
import org.getshaka.nativeconverter.{NativeConverter, ParseState}
import zio.*
import zio.test.*

import scala.scalajs.js

object ZHubKvSpec extends ZIOSpecDefault:
    final case class Entry(name: String, count: Int, enabled: Boolean) derives NativeConverter
    final case class Broken(value: String)

    /** Installs a fake h34s Nitro startup record whose `storage(base)` returns `native`. */
    private def installPlugin(native: js.Any, onMount: String => Unit = _ => ()): UIO[Unit] =
      ZIO.succeed(NitroGlobal.install(js.Dynamic.literal(
        version = 1,
        development = false,
        storage = ((base: String) => { onMount(base); native }): js.Function1[String, js.Any],
      ).asInstanceOf[js.Object]))

    /** Opens `native` as an application does: `ZHubKv.mount` over the Nitro startup record. */
    private def mounted(native: NativeStorage, base: String = "kv"): IO[ConfigError, ZHubKv] =
      installPlugin(native) *> ZHubKv.mount(base)

    private val removePlugin = ZIO.succeed(NitroGlobal.remove())

    /** Writes a raw native value, bypassing the converters. */
    private def rawSet(native: NativeStorage, key: String, value: js.Any): Task[Unit] =
      ZIO.fromPromiseJS(native.asInstanceOf[js.Dynamic].set(key, value).asInstanceOf[js.Promise[Unit]])

    def spec = suite("ZHubKv")(
      test("mount opens the requested Nitro storage mount through the h34s startup record") {
        var requested = List.empty[String]
        for
          _ <- installPlugin(KvTestFixtures.memory(), base => requested :+= base)
          kv <- ZHubKv.mount("cache")
          _ <- kv.set("item", Entry("cached", 1, true))
          found <- kv.get[Entry]("item")
        yield assertTrue(found.contains(Entry("cached", 1, true)), requested == List("cache"))
      },
      test("without the h34s Nitro plugin, or with an unavailable mount, mount fails with ConfigError") {
        for
          _ <- removePlugin
          missing <- ZHubKv.mount("kv").either
          _ <- ZIO.succeed(NitroGlobal.install(js.Dynamic.literal(version = 1, development = false).asInstanceOf[js.Object]))
          outdated <- ZHubKv.mount("kv").either
          _ <- installPlugin(js.undefined)
          unavailable <- ZHubKv.mount("kv").either
        yield assertTrue(
          missing.left.map(_.message) == Left("h34s Nitro plugin is not installed"),
          outdated.left.map(_.message) == Left("h34s Nitro plugin is not installed"),
          unavailable.left.map(_.message) == Left("Nitro storage mount 'kv' is not available"),
        )
      },
      // H1
      test("uses NuxtHub's generated memory-KV shape with NativeConverter models") {
        val entry = Entry("42", 0, false)
        for
          kv <- mounted(KvTestFixtures.memory())
          absent <- kv.get[Entry]("scope:entry")
          _ <- kv.set("scope/entry", entry)
          found <- kv.get[Entry]("scope:entry")
          exists <- kv.has("scope:entry")
          keys <- kv.keys(Some("scope"))
          _ <- kv.delete("scope:entry")
          deleted <- kv.get[Entry]("scope:entry")
          remains <- kv.has("scope:entry")
        yield assertTrue(absent.isEmpty, found.contains(entry), exists, keys == List("scope:entry"), deleted.isEmpty, !remains)
      },
      // H2
      test("distinguishes false and zero from missing values") {
        for
          kv <- mounted(KvTestFixtures.memory())
          _ <- kv.set("false", false)
          _ <- kv.set("zero", 0)
          bool <- kv.get[Boolean]("false")
          number <- kv.get[Int]("zero")
        yield assertTrue(bool.contains(false), number.contains(0))
      },
      // H3
      test("preserves native stored-null and undefined-deletion semantics without calling the decoder") {
        val native = KvTestFixtures.memory()
        var decodes = 0
        given NativeConverter[Broken]:
            extension (value: Broken) def toNative: js.Any = value.value
            def fromNative(state: ParseState): Broken =
              decodes += 1
              Broken(state.json.asInstanceOf[String])
        for
          kv <- mounted(native)
          _ <- rawSet(native, "entry", null)
          storedNull <- kv.get[Broken]("entry")
          exists <- kv.has("entry")
          _ <- rawSet(native, "entry", js.undefined)
          missing <- kv.get[Broken]("entry")
          remains <- kv.has("entry")
        yield assertTrue(storedNull.isEmpty, exists, missing.isEmpty, !remains, decodes == 0)
      },
      // H4, H8
      test("defers native calls and conversion until each execution, forwarding TTL") {
        val recording = new KvTestFixtures.Recording
        var conversions = 0
        given NativeConverter[Broken]:
            extension (value: Broken) def toNative: js.Any =
              conversions += 1
              value.value
            def fromNative(state: ParseState): Broken = Broken(state.json.asInstanceOf[String])
        for
          kv <- mounted(recording.native)
          write = kv.set("item", Broken("hello"), Some(12.5))
          read = kv.get[Broken]("item")
          exists = kv.has("item")
          remove = kv.delete("item")
          list = kv.keys(Some("scope"))
          deferred = recording.calls.isEmpty && conversions == 0
          _ <- write
          _ <- write
          _ <- read
          _ <- exists
          _ <- remove
          keys <- list
          _ <- kv.set("plain", Broken("x"))
        yield
          val optionsOmitted = js.isUndefined(recording.options)
          assertTrue(
            deferred, conversions == 3, recording.stored.asInstanceOf[String] == "x",
            recording.calls == List("set" -> "item", "set" -> "item", "get" -> "item", "has" -> "item", "del" -> "item", "keys" -> "scope", "set" -> "plain"),
            keys == List("scope:item"), optionsOmitted,
          )
      },
      test("forwards the TTL in seconds exactly") {
        val recording = new KvTestFixtures.Recording
        for
          kv <- mounted(recording.native)
          _ <- kv.set("item", "value", Some(12.5))
        yield
          val ttl = recording.options.toOption.map(_.ttl)
          assertTrue(ttl.contains(12.5))
      },
      // H5
      test("captures synchronous native failures as typed failures, without invoking operations during construction") {
        val recording = new KvTestFixtures.Recording
        val error = new RuntimeException("synchronous storage failure")
        recording.failure = Some(error)
        for
          kv <- mounted(recording.native)
          operations = List(kv.get[String]("key").unit, kv.set("key", "value"), kv.has("key").unit, kv.delete("key"), kv.keys().unit)
          deferred = recording.calls.isEmpty
          results <- ZIO.foreach(operations)(_.either)
        yield assertTrue(deferred, results.forall(_.left.exists(_ eq error)), recording.calls.size == 5)
      },
      test("captures rejected native Promises in the failure channel") {
        val recording = new KvTestFixtures.Recording
        val error = new RuntimeException("rejected storage operation")
        recording.failure = Some(error)
        recording.reject = true
        for
          kv <- mounted(recording.native)
          results <- ZIO.foreach(List(kv.get[String]("key").unit, kv.set("key", "value"), kv.has("key").unit, kv.delete("key"), kv.keys().unit))(_.either)
        yield assertTrue(results.forall(_.left.exists(_ eq error)), recording.calls.size == 5)
      },
      // H6
      test("captures actual JavaScript errors from throws and rejected Promises") {
        val thrown = js.Dynamic.literal(
          get = ((_: String) => throw js.JavaScriptException(new js.Error("native throw"))): js.Function1[String, js.Promise[js.Any]],
        ).asInstanceOf[NativeStorage]
        val rejected = js.Dynamic.literal(
          get = ((_: String) => js.Promise.reject(new js.Error("native rejection"))): js.Function1[String, js.Promise[js.Any]],
        ).asInstanceOf[NativeStorage]
        for
          first <- mounted(thrown).flatMap(_.get[String]("key")).either
          second <- mounted(rejected).flatMap(_.get[String]("key")).either
        yield assertTrue(
          first.left.exists(_.getMessage.contains("native throw")),
          second.left.exists(_.getMessage.contains("native rejection")),
        )
      },
      // H7
      test("reports conversion errors and does not write when encoding fails") {
        val recording = new KvTestFixtures.Recording
        val error = new RuntimeException("invalid stored representation")
        given NativeConverter[Broken]:
            extension (value: Broken) def toNative: js.Any = throw error
            def fromNative(state: ParseState): Broken = throw error
        recording.result = "unexpected"
        for
          kv <- mounted(recording.native)
          read <- kv.get[Broken]("key").either
          write <- kv.set("key", Broken("value")).either
        yield assertTrue(read.left.exists(_ eq error), write.left.exists(_ eq error), recording.calls == List("get" -> "key"))
      },
      // H9
      test("does not hide upstream primitive-string deserialization") {
        for
          kv <- mounted(KvTestFixtures.memory())
          _ <- kv.set("number-text", "42")
          read <- kv.get[String]("number-text").either
        yield assertTrue(read.isLeft)
      },
      // H10
      test("interrupting the wait does not cancel an already-started native write") {
        var complete: () => Unit = () => ()
        var started = false
        var persisted = false
        val pending = new js.Promise[Unit]((resolve, _) => complete = () => { resolve(()); () })
        val stored = pending.`then`[Unit] { (_: Unit) => persisted = true; () }
        val native = js.Dynamic.literal(
          set = ((_: String, _: js.Any, _: js.Any) => { started = true; stored }): js.Function3[String, js.Any, js.Any, js.Promise[Unit]],
        ).asInstanceOf[NativeStorage]
        for
          kv <- mounted(native)
          fiber <- kv.set("key", "value").fork
          _ <- ZIO.yieldNow.repeatUntil(_ => started)
          _ <- fiber.interrupt
          before = persisted
          _ <- ZIO.succeed(complete())
          _ <- ZIO.fromPromiseJS(stored)
        yield assertTrue(!before, persisted)
      },
    ) @@ TestAspect.sequential @@ TestAspect.after(removePlugin)
end ZHubKvSpec
