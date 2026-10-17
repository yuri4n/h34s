package dev.yuri4n.exts.kyoh3

import dev.yuri4n.exts.kyoh3.KyoTesting.*
import kyo.*
import munit.FunSuite
import org.getshaka.nativeconverter.NativeConverter

import scala.concurrent.ExecutionContext
import scala.scalajs.js

class RuntimeConfigSuite extends FunSuite:
    given ExecutionContext = ExecutionContext.global

    final case class Database(url: Secret) derives NativeConverter
    final case class AppConfig(database: Database, apiBase: String) derives NativeConverter

    private def record(config: => js.Any): js.Object =
      js.Dynamic.literal(
        version = 1,
        development = false,
        runtimeConfig = (() => config): js.Function0[js.Any],
      )

    private val valid = js.JSON.parse("{\"database\":{\"url\":\"postgres://user:hunter2@db:5432/app\"},\"apiBase\":\"/api\"}")

    /** Runs `RuntimeConfig.get` inside a handler and renders either the config or the error message. */
    private def configured(call: Call) = call.invoke(KHandler.define { request =>
      Abort.run[ConfigError](RuntimeConfig.get[AppConfig](request)).map(_.fold(
        config => s"${config.apiBase} ${config.database.url}",
        error => s"ConfigError: ${error.message}",
        error => throw error,
      ))
    })

    // C11
    test("RuntimeConfig.get decodes the request's Nuxt runtime config") {
      configured(Call(record = Some(record(valid)))).map { body =>
        assertEquals(body.asInstanceOf[String], "/api Secret(<redacted>)")
      }
    }

    test("decode failures name the path and never the value") {
      val wrong = js.JSON.parse("{\"database\":{\"url\":12345678},\"apiBase\":\"/api\"}")
      configured(Call(record = Some(record(wrong)))).map { body =>
        val message = body.asInstanceOf[String]
        assertEquals(message, "ConfigError: Nuxt runtime config does not match the expected shape at root.database.url")
        assert(!message.contains("12345678"))
      }
    }

    test("a config read that throws is a ConfigError without its message") {
      configured(Call(record = Some(record(throw IllegalStateException("hunter2"))))).map { body =>
        assertEquals(body.asInstanceOf[String], "ConfigError: h34s could not read the Nuxt runtime config")
      }
    }

    test("without the h34s Nitro plugin, config access fails with ConfigError") {
      val oldPlugin = js.Dynamic.literal(development = true).asInstanceOf[js.Object]
      for
        missing <- configured(Call())
        outdated <- configured(Call(record = Some(oldPlugin)))
      yield
        assertEquals(missing.asInstanceOf[String], "ConfigError: h34s Nitro plugin is not installed")
        assertEquals(outdated.asInstanceOf[String], "ConfigError: h34s Nitro plugin is not installed")
    }

    test("an unhandled ConfigError is an opaque 500") {
      val call = Call(record = Some(record(js.Dynamic.literal())))
      call.invoke(KHandler.define(request => RuntimeConfig.get[AppConfig](request).map(_.apiBase))).map { body =>
        assertEquals(call.response.status, 500)
        assertOpaque(body)
      }
    }

    test("RuntimeConfig.load reads the startup config") {
      NitroGlobal.install(record(valid))
      run(RuntimeConfig.load[AppConfig]).map { result =>
        NitroGlobal.remove()
        assertEquals(result.map(_.apiBase), Result.succeed("/api"))
      }.flatMap { _ =>
        run(RuntimeConfig.load[AppConfig]).map { result =>
          assertEquals(result.failure.map(_.getMessage), Maybe("h34s Nitro plugin is not installed"))
        }
      }
    }
end RuntimeConfigSuite
