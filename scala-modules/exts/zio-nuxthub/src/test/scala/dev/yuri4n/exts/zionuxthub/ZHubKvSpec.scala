package dev.yuri4n.exts.zionuxthub

import dev.yuri4n.exts.nuxthub.{HubKv, KvTestFixtures}
import org.getshaka.nativeconverter.{NativeConverter, ParseState}
import zio.*
import zio.test.*

import scala.scalajs.js

object ZHubKvSpec extends ZIOSpecDefault:
    final case class Entry(name: String, count: Int, enabled: Boolean) derives NativeConverter
    final case class Broken(value: String)

    def spec = suite("ZHubKv")(
      test("uses NuxtHub's generated memory-KV shape with NativeConverter models") {
        val kv = ZHubKv(KvTestFixtures.memory())
        val entry = Entry("42", 0, false)
        for
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
      test("distinguishes false and zero from missing values") {
        val kv = ZHubKv(KvTestFixtures.memory())
        for
          _ <- kv.set("false", false)
          _ <- kv.set("zero", 0)
          bool <- kv.get[Boolean]("false")
          number <- kv.get[Int]("zero")
        yield assertTrue(bool.contains(false), number.contains(0))
      },
      test("preserves native stored-null and undefined-deletion semantics") {
        val native = KvTestFixtures.memory()
        val kv = ZHubKv(native)
        for
          _ <- ZIO.fromPromiseJS(native.set("entry", null))
          storedNull <- kv.get[Entry]("entry")
          exists <- kv.has("entry")
          _ <- ZIO.fromPromiseJS(native.set("entry", js.undefined))
          missing <- kv.get[Entry]("entry")
          remains <- kv.has("entry")
        yield assertTrue(storedNull.isEmpty, exists, missing.isEmpty, !remains)
      },
      test("defers native calls and conversion until each execution, forwarding TTL and aliases") {
        val recording = new KvTestFixtures.Recording
        val kv = ZHubKv(recording.native)
        var conversions = 0
        given NativeConverter[Broken] with
            extension (value: Broken) def toNative: js.Any =
              conversions += 1
              value.value
            def fromNative(state: ParseState): Broken = Broken(state.json.asInstanceOf[String])
        val write = kv.set("item", Broken("hello"), Some(60))
        val read = kv.get[Broken]("item")
        val exists = kv.has("item")
        val remove = kv.delete("item")
        val list = kv.keys(Some("scope"))
        val deferred = recording.calls.isEmpty && conversions == 0
        for
          _ <- write
          _ <- write
          _ <- read
          _ <- exists
          _ <- remove
          keys <- list
        yield
          val ttl = recording.options.toOption.map(_.ttl)
          assertTrue(
            deferred, conversions == 2, recording.stored.asInstanceOf[String] == "hello", ttl.contains(60.0),
            recording.calls == List("set" -> "item", "set" -> "item", "get" -> "item", "has" -> "item", "del" -> "item", "keys" -> "scope"),
            keys == List("scope:item"),
          )
      },
      test("captures synchronous native failures without invoking operations during construction") {
        val recording = new KvTestFixtures.Recording
        val error = new RuntimeException("synchronous storage failure")
        recording.failure = Some(error)
        val kv = ZHubKv(recording.native)
        val operations = List(kv.get[String]("key").unit, kv.set("key", "value"), kv.has("key").unit, kv.delete("key"), kv.keys().unit)
        val deferred = recording.calls.isEmpty
        for results <- ZIO.foreach(operations)(_.either)
        yield assertTrue(deferred, results.forall(_ == Left(error)), recording.calls.size == 5)
      },
      test("captures rejected native Promises in the failure channel") {
        val recording = new KvTestFixtures.Recording
        val error = new RuntimeException("rejected storage operation")
        recording.failure = Some(error)
        recording.reject = true
        val kv = ZHubKv(recording.native)
        for results <- ZIO.foreach(List(kv.get[String]("key").unit, kv.set("key", "value"), kv.has("key").unit, kv.delete("key"), kv.keys().unit))(_.either)
        yield assertTrue(results.forall(_ == Left(error)), recording.calls.size == 5)
      },
      test("captures actual JavaScript errors from throws and rejected Promises") {
        val thrown = js.Dynamic.literal(
          get = ((_: String) => throw js.JavaScriptException(new js.Error("native throw"))): js.Function1[String, js.Promise[js.Any]],
        ).asInstanceOf[HubKv]
        val rejected = js.Dynamic.literal(
          get = ((_: String) => js.Promise.reject(new js.Error("native rejection"))): js.Function1[String, js.Promise[js.Any]],
        ).asInstanceOf[HubKv]
        for
          first <- ZHubKv(thrown).get[String]("key").either
          second <- ZHubKv(rejected).get[String]("key").either
        yield assertTrue(
          first.left.exists(_.getMessage.contains("native throw")),
          second.left.exists(_.getMessage.contains("native rejection")),
        )
      },
      test("reports conversion errors and does not write when encoding fails") {
        val recording = new KvTestFixtures.Recording
        val error = new RuntimeException("invalid stored representation")
        given NativeConverter[Broken] with
            extension (value: Broken) def toNative: js.Any = throw error
            def fromNative(state: ParseState): Broken = throw error
        val kv = ZHubKv(recording.native)
        recording.result = "unexpected"
        for
          read <- kv.get[Broken]("key").either
          write <- kv.set("key", Broken("value")).either
        yield assertTrue(read == Left(error), write == Left(error), recording.calls == List("get" -> "key"))
      },
      test("does not hide upstream primitive-string deserialization") {
        val kv = ZHubKv(KvTestFixtures.memory())
        for
          _ <- kv.set("number-text", "42")
          read <- kv.get[String]("number-text").either
        yield assertTrue(read.isLeft)
      },
      test("interrupting the wait does not cancel an already-started native write") {
        var complete: () => Unit = () => ()
        var started = false
        var persisted = false
        val pending = new js.Promise[Unit]((resolve, _) => complete = () => { resolve(()); () })
        val stored = pending.`then`[Unit] { (_: Unit) => persisted = true; () }
        val native = js.Dynamic.literal(
          set = ((_: String, _: js.Any, _: js.Any) => { started = true; stored }): js.Function3[String, js.Any, js.Any, js.Promise[Unit]],
        ).asInstanceOf[HubKv]
        for
          fiber <- ZHubKv(native).set("key", "value").fork
          _ <- ZIO.yieldNow.repeatUntil(_ => started)
          _ <- fiber.interrupt
          before = persisted
          _ <- ZIO.succeed(complete())
          _ <- ZIO.fromPromiseJS(stored)
        yield assertTrue(!before, persisted)
      },
    )
