package dev.yuri4n.exts.kyonuxthub

import dev.yuri4n.exts.h3.internal.NativeStorage
import kyo.*
import munit.FunSuite
import org.getshaka.nativeconverter.{NativeConverter, ParseState}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.scalajs.js

class KHubKvSuite extends FunSuite:
    given ExecutionContext = ExecutionContext.global
    override val munitTimeout: FiniteDuration = 60.seconds

    final case class Entry(name: String, count: Int, enabled: Boolean) derives NativeConverter

    private def run[A](program: => A < (Async & Abort[Throwable])): Future[Result[Throwable, A]] =
      import kyo.AllowUnsafe.embrace.danger
      Fiber.Unsafe.init(Abort.run[Throwable](program)).toFuture()

    private def success[A](result: Result[Throwable, A]): A =
      result.fold(
        identity,
        error => fail(s"Unexpected typed failure: ${error.getMessage}"),
        error => fail(s"Unexpected panic: ${error.getMessage}"),
      )

    private def aborted[A](result: Result[Throwable, A], expected: Throwable): Unit =
      assert(result.isFailure, "the operation must fail through Abort[Throwable], not panic")
      result.fold(
        _ => fail("Expected a typed failure"),
        error => assert(error eq expected),
        error => fail(s"Expected a typed failure, got panic: ${error.getMessage}"),
      )

    private def nextTurn: Future[Unit] =
      val completed = Promise[Unit]()
      js.timers.setTimeout(0) { completed.success(()); () }
      completed.future

    test("real NuxtHub memory storage round trips DTOs and exposes missing, has, keys and delete") {
      val kv = KHubKv.fromNative(KvTestFixtures.memory())
      val first = Entry("42", 0, false)
      val second = Entry("second", 2, true)
      run {
        for
          absent <- kv.get[Entry]("scope:first")
          initiallyPresent <- kv.has("scope:first")
          _ <- kv.set("scope/first", first)
          _ <- kv.set("scope:nested:second", second)
          _ <- kv.set("other:item", second)
          fetched <- kv.get[Entry]("scope:first")
          present <- kv.has("scope:first")
          scopedKeys <- kv.keys(Some("scope"))
          allKeys <- kv.keys()
          _ <- kv.delete("scope:first")
          deleted <- kv.get[Entry]("scope:first")
          stillPresent <- kv.has("scope:first")
          _ <- kv.delete("scope:first")
        yield (absent, initiallyPresent, fetched, present, scopedKeys, allKeys, deleted, stillPresent)
      }.map { result =>
        val (absent, initiallyPresent, fetched, present, scopedKeys, allKeys, deleted, stillPresent) = success(result)
        assertEquals(absent, None)
        assert(!initiallyPresent)
        assertEquals(fetched, Some(first))
        assert(present)
        assertEquals(scopedKeys.toSet, Set("scope:first", "scope:nested:second"))
        assertEquals(allKeys.toSet, Set("scope:first", "scope:nested:second", "other:item"))
        assertEquals(deleted, None)
        assert(!stillPresent)
      }
    }

    test("false and zero are stored values rather than missing values") {
      val kv = KHubKv.fromNative(KvTestFixtures.memory())
      run {
        for
          _ <- kv.set("disabled", false)
          _ <- kv.set("zero", 0)
          disabled <- kv.get[Boolean]("disabled")
          zero <- kv.get[Int]("zero")
          hasDisabled <- kv.has("disabled")
          hasZero <- kv.has("zero")
        yield (disabled, zero, hasDisabled, hasZero)
      }.map { result =>
        val (disabled, zero, hasDisabled, hasZero) = success(result)
        assertEquals(disabled, Some(false))
        assertEquals(zero, Some(0))
        assert(hasDisabled && hasZero)
      }
    }

    test("native null and undefined become None without calling the decoder") {
      final case class Decoded(value: String)
      var decodes = 0
      given NativeConverter[Decoded]:
        extension (value: Decoded) def toNative: js.Any = value.value
        def fromNative(state: ParseState): Decoded =
          decodes += 1
          Decoded(state.json.asInstanceOf[String])

      Future.traverse(List[js.Any](null, js.undefined)) { missing =>
        val recording = new KvTestFixtures.Recording
        recording.result = missing
        run(KHubKv.fromNative(recording.native).get[Decoded]("missing")).map { result =>
          assertEquals(success(result), None)
          assertEquals(recording.calls, List("get" -> "missing"))
        }
      }.map { _ => assertEquals(decodes, 0) }
    }

    test("prepared operations are lazy and repeat native calls and conversions on every run") {
      final case class Counted(value: String)
      var encodes = 0
      var decodes = 0
      given NativeConverter[Counted]:
        extension (value: Counted) def toNative: js.Any =
          encodes += 1
          value.value
        def fromNative(state: ParseState): Counted =
          decodes += 1
          Counted(state.json.asInstanceOf[String])

      val recording = new KvTestFixtures.Recording
      recording.result = "first"
      val kv = KHubKv.fromNative(recording.native)
      val write = kv.set("item", Counted("stored"))
      val read = kv.get[Counted]("item")
      val exists = kv.has("item")
      val listed = kv.keys(Some("scope"))
      val deleted = kv.delete("item")
      assertEquals(recording.calls, Nil)
      assertEquals(encodes, 0)
      assertEquals(decodes, 0)

      def program =
        for
          _ <- write
          value <- read
          present <- exists
          keys <- listed
          _ <- deleted
        yield (value, present, keys)

      for
        first <- run(program)
        _ = assertEquals(success(first), (Some(Counted("first")), true, List("scope:item")))
        _ = recording.result = "second"
        second <- run(program)
      yield
        assertEquals(success(second), (Some(Counted("second")), true, List("scope:item")))
        assertEquals(encodes, 2)
        assertEquals(decodes, 2)
        assertEquals(recording.stored.asInstanceOf[String], "stored")
        val once = List("set" -> "item", "get" -> "item", "has" -> "item", "keys" -> "scope", "del" -> "item")
        assertEquals(recording.calls, once ++ once)
    }

    for reject <- List(false, true) do
      test(s"all native methods turn ${if reject then "rejected Promises" else "synchronous throws"} into typed failures") {
        Future.traverse(List("get", "set", "has", "delete", "keys")) { method =>
          val recording = new KvTestFixtures.Recording
          val error = new IllegalStateException(s"$method failed")
          recording.failure = Some(error)
          recording.reject = reject
          val kv = KHubKv.fromNative(recording.native)
          val program: Unit < (Async & Abort[Throwable]) = method match
              case "get"    => kv.get[Entry]("key").map(_ => ())
              case "set"    => kv.set("key", Entry("value", 1, true))
              case "has"    => kv.has("key").map(_ => ())
              case "delete" => kv.delete("key")
              case _        => kv.keys(Some("key")).map(_ => ())
          assertEquals(recording.calls, Nil)
          run(program).map { result =>
            aborted(result, error)
            assertEquals(recording.calls, List((if method == "delete" then "del" else method) -> "key"))
          }
        }.map(_ => ())
      }

    test("raw JavaScript errors enter Abort[Throwable] with their original messages") {
      Future.traverse(List(false, true)) { reject =>
        val message = if reject then "native rejection" else "native synchronous error"
        val error = new js.Error(message)
        val get: js.Function1[String, js.Promise[js.Any]] = _ =>
          if reject then js.Promise.reject(error)
          else throw js.JavaScriptException(error)
        val native = js.Dynamic.literal(get = get).asInstanceOf[NativeStorage]
        run(KHubKv.fromNative(native).get[Entry]("key")).map { result =>
          assert(result.isFailure, "raw JavaScript errors must use the typed failure channel")
          result.fold(
            _ => fail("Expected the JavaScript error"),
            failure => assert(failure.getMessage.contains(message)),
            panic => fail(s"Expected a typed failure, got panic: ${panic.getMessage}"),
          )
        }
      }.map(_ => ())
    }

    test("decoder exceptions enter Abort[Throwable] after the native read") {
      final case class Broken(value: String)
      val error = new IllegalArgumentException("invalid stored value")
      given NativeConverter[Broken]:
        extension (value: Broken) def toNative: js.Any = value.value
        def fromNative(state: ParseState): Broken = throw error

      val recording = new KvTestFixtures.Recording
      recording.result = js.Dynamic.literal(value = "bad")
      run(KHubKv.fromNative(recording.native).get[Broken]("broken")).map { result =>
        aborted(result, error)
        assertEquals(recording.calls, List("get" -> "broken"))
      }
    }

    test("encoder exceptions enter Abort[Throwable] without calling the native writer") {
      final case class Broken(value: String)
      val error = new IllegalArgumentException("cannot encode")
      var encodes = 0
      given NativeConverter[Broken]:
        extension (value: Broken) def toNative: js.Any =
          encodes += 1
          throw error
        def fromNative(state: ParseState): Broken = Broken(state.json.asInstanceOf[String])

      val recording = new KvTestFixtures.Recording
      val program = KHubKv.fromNative(recording.native).set("broken", Broken("value"))
      assertEquals(encodes, 0)
      for
        first <- run(program)
        second <- run(program)
      yield
        aborted(first, error)
        aborted(second, error)
        assertEquals(encodes, 2)
        assertEquals(recording.calls, Nil)
        assert(js.isUndefined(recording.stored))
    }

    test("TTL seconds are forwarded exactly and omitted when absent") {
      val recording = new KvTestFixtures.Recording
      val kv = KHubKv.fromNative(recording.native)
      for
        withTtl <- run(kv.set("expiring", Entry("value", 1, true), Some(12.5)))
        _ = success(withTtl)
        _ = assertEquals(recording.options.get.ttl, 12.5)
        withoutTtl <- run(kv.set("persistent", Entry("value", 1, true)))
      yield
        success(withoutTtl)
        assert(js.isUndefined(recording.options))
        assertEquals(recording.calls, List("set" -> "expiring", "set" -> "persistent"))
    }

    test("interruption stops the Kyo continuation without cancelling the native Promise") {
      import kyo.AllowUnsafe.embrace.danger
      val started = Promise[Unit]()
      var nativeCompleted = false
      var continued = false
      var completeNative: () => Unit = () => ()
      val pending = new js.Promise[js.Any]((resolve, _) => {
        completeNative = () => {
          if !nativeCompleted then
            nativeCompleted = true
            resolve(js.Dynamic.literal(name = "late", count = 1, enabled = true))
        }
      })
      val native = js.Dynamic.literal(
        get = ((_: String) => { started.success(()); pending }): js.Function1[String, js.Promise[js.Any]],
      ).asInstanceOf[NativeStorage]
      val program = KHubKv.fromNative(native).get[Entry]("pending").map { value =>
        Sync.defer { continued = true; value }
      }
      val fiber = Fiber.Unsafe.init(Abort.run[Throwable](program))
      val running = fiber.toFuture()
      val checked = for
        _ <- started.future
        _ = assert(!nativeCompleted)
        _ = assert(fiber.interrupt())
        stopped <- running.transform(result => scala.util.Success(result))
        _ = assert(stopped.isFailure, "the interrupted fiber must settle before its native Promise")
        _ = assert(!nativeCompleted)
        _ = completeNative()
        _ <- pending.toFuture
        _ <- nextTurn
      yield
        assert(nativeCompleted, "the native operation can still finish after Kyo interruption")
        assert(!continued, "late Promise completion must not resume the interrupted continuation")
      checked.andThen { case _ => fiber.interrupt(); completeNative() }
    }
end KHubKvSuite
