package dev.yuri4n.exts.kyonuxthub

import dev.yuri4n.exts.h3.{ConfigError, ErrorEntry, HttpError, InvalidInput, SafeError}
import dev.yuri4n.exts.h3.internal.NativeStorage
import dev.yuri4n.exts.kyoh3.{KHandler, KyoTesting, NitroGlobal}
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

    /** Bases requested from the fake startup record's `storage`, in order. */
    private var requestedBases = List.empty[String]

    /** A fake h34s Nitro startup record whose `storage(base)` returns `native`. */
    private def installPlugin(native: js.Any): Unit =
      NitroGlobal.install(js.Dynamic.literal(
        version = 1,
        development = false,
        storage = ((base: String) => { requestedBases :+= base; native }): js.Function1[String, js.Any],
      ).asInstanceOf[js.Object])

    /** Opens `native` as an application does: `KHubKv.mount` over the Nitro startup record. */
    private def mounted(native: NativeStorage, base: String = "kv"): KHubKv =
      import kyo.AllowUnsafe.embrace.danger
      installPlugin(native)
      Sync.Unsafe.evalOrThrow(KHubKv.mount(base))

    override def beforeEach(context: BeforeEach): Unit = requestedBases = Nil

    override def afterEach(context: AfterEach): Unit = NitroGlobal.remove()

    test("mount opens the requested Nitro storage mount through the h34s startup record") {
      val kv = mounted(KvTestFixtures.memory(), "cache")
      assertEquals(requestedBases, List("cache"))
      run(kv.set("item", Entry("cached", 1, true)).andThen(kv.get[Entry]("item"))).map { result =>
        assertEquals(success(result), Some(Entry("cached", 1, true)))
        assertEquals(requestedBases, List("cache"), "operations reuse the mount")
      }
    }

    test("without the h34s Nitro plugin, mount fails with ConfigError") {
      def configError(result: Result[Throwable, KHubKv]): String =
        result.fold(
          _ => fail("Expected a ConfigError"),
          {
            case error: ConfigError => error.message
            case other              => fail(s"Expected a ConfigError, got $other")
          },
          error => fail(s"Expected a typed failure, got panic: ${error.getMessage}"),
        )
      val outdatedPlugin = js.Dynamic.literal(version = 1, development = false).asInstanceOf[js.Object]
      for
        missing <- run(KHubKv.mount("kv"))
        _ = NitroGlobal.install(outdatedPlugin)
        outdated <- run(KHubKv.mount("kv"))
        _ = installPlugin(js.undefined)
        unavailable <- run(KHubKv.mount("kv"))
      yield
        assertEquals(configError(missing), "h34s Nitro plugin is not installed")
        assertEquals(configError(outdated), "h34s Nitro plugin is not installed")
        assertEquals(configError(unavailable), "Nitro storage mount 'kv' is not available")
    }

    test("real NuxtHub memory storage round trips DTOs and exposes missing, has, keys and delete") {
      val kv = mounted(KvTestFixtures.memory())
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
      val kv = mounted(KvTestFixtures.memory())
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
        run(mounted(recording.native).get[Decoded]("missing")).map { result =>
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
      val kv = mounted(recording.native)
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
          val kv = mounted(recording.native)
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
        run(mounted(native).get[Entry]("key")).map { result =>
          assert(result.isFailure, "raw JavaScript errors must use the typed failure channel")
          result.fold(
            _ => fail("Expected the JavaScript error"),
            failure => assert(failure.getMessage.contains(message)),
            panic => fail(s"Expected a typed failure, got panic: ${panic.getMessage}"),
          )
        }
      }.map(_ => ())
    }

    test("decoder exceptions become a value-free StoredValueError after the native read") {
      final case class Broken(value: String)
      given NativeConverter[Broken]:
        extension (value: Broken) def toNative: js.Any = value.value
        def fromNative(state: ParseState): Broken = throw new IllegalArgumentException("invalid stored value: private")
      final case class Session(user: String, refreshToken: String, count: Int) derives NativeConverter
      final case class Checked(title: String)
      given NativeConverter[Checked]:
        extension (value: Checked) def toNative: js.Any = value.title
        def fromNative(state: ParseState): Checked =
          throw InvalidInput(List(ErrorEntry("must not be blank", Some("title"))))

      val broken = new KvTestFixtures.Recording
      broken.result = js.Dynamic.literal(value = "bad")
      val session = new KvTestFixtures.Recording
      session.result = js.JSON.parse("{\"user\":\"alice\",\"refreshToken\":\"rt-private-123\",\"count\":\"many\"}")
      val checked = new KvTestFixtures.Recording
      checked.result = js.Dynamic.literal(title = " ")
      def stored(result: Result[Throwable, Any]): StoredValueError =
        result.fold(
          value => fail(s"expected a failure, got $value"),
          {
            case error: StoredValueError => error
            case other                   => fail(s"expected StoredValueError, got $other")
          },
          error => fail(s"expected a typed failure, got panic $error"),
        )
      for
        custom <- run(mounted(broken.native).get[Broken]("session:abc"))
        shape <- run(mounted(session.native).get[Session]("session:abc"))
        schema <- run(mounted(checked.native).get[Checked]("note:1"))
      yield
        assertEquals(stored(custom), StoredValueError(None))
        assertEquals(stored(shape), StoredValueError(Some("root.count")))
        assert(!stored(shape).getMessage.contains("rt-private-123"), stored(shape).getMessage)
        assert(!stored(shape).getMessage.contains("session:abc"), stored(shape).getMessage)
        val schemaError: Throwable = stored(schema)
        assert(!schemaError.isInstanceOf[SafeError], "a converter's InvalidInput must not become an approved error")
        assertEquals(stored(schema), StoredValueError(Some("title")))
        assertEquals(stored(shape).getCause, null)
        assertEquals(broken.calls, List("get" -> "session:abc"))
    }

    test("an unhandled StoredValueError in a handler is an opaque 500, not a 422") {
      final case class Checked(title: String)
      given NativeConverter[Checked]:
        extension (value: Checked) def toNative: js.Any = value.title
        def fromNative(state: ParseState): Checked =
          throw InvalidInput(List(ErrorEntry("must not be blank", Some("title"))))
      val recording = new KvTestFixtures.Recording
      recording.result = js.Dynamic.literal(title = " ")
      val kv = mounted(recording.native)
      val call = KyoTesting.Call()
      call.invoke(KHandler.define(_ => kv.get[Checked]("note:1").map(_.fold("missing")(_.title)))).map { body =>
        assertEquals(call.response.status, 500)
        KyoTesting.assertOpaque(body)
      }
    }

    test("a SafeError thrown by an encoder is not an approved error") {
      final case class Leaky(value: String)
      given NativeConverter[Leaky]:
        extension (value: Leaky) def toNative: js.Any = throw HttpError.conflict("private encoder detail")
        def fromNative(state: ParseState): Leaky = Leaky("")
      val recording = new KvTestFixtures.Recording
      run(mounted(recording.native).set("leaky", Leaky("x"))).map { result =>
        result.fold(
          _ => fail("expected a failure"),
          error => assert(error.isInstanceOf[IllegalStateException] && !error.isInstanceOf[SafeError], error.toString),
          error => fail(s"expected a typed failure, got panic $error"),
        )
        assertEquals(recording.calls, Nil)
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
      val program = mounted(recording.native).set("broken", Broken("value"))
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
      val kv = mounted(recording.native)
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
      val program = mounted(native).get[Entry]("pending").map { value =>
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
