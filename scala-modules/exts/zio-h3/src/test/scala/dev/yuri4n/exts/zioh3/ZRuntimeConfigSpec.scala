package dev.yuri4n.exts.zioh3

import dev.yuri4n.exts.kyoh3.NitroGlobal
import dev.yuri4n.exts.zioh3.ZioTesting.*
import org.getshaka.nativeconverter.NativeConverter
import zio.*
import zio.test.*

import scala.scalajs.js

/** Nuxt runtime config for ZIO handlers (C11). */
object ZRuntimeConfigSpec extends ZIOSpecDefault:
    final case class Database(url: Secret) derives NativeConverter
    final case class AppConfig(database: Database, apiBase: String) derives NativeConverter

    private def record(config: => js.Any): js.Object =
      js.Dynamic.literal(
        version = 1,
        development = false,
        runtimeConfig = (() => config): js.Function0[js.Any],
      )

    private def valid = js.JSON.parse("{\"database\":{\"url\":\"postgres://user:hunter2@db:5432/app\"},\"apiBase\":\"/api\"}")

    /** Runs `ZRuntimeConfig.get` inside a handler and renders either the config or the error message. */
    private def configured(call: Call): Task[String] =
      call.invoke(ZHandler.define { request =>
        ZRuntimeConfig.get[AppConfig](request).fold(
          error => s"ConfigError: ${error.message}",
          config => s"${config.apiBase} ${config.database.url}",
        )
      }).map(body => data(body).asInstanceOf[String])

    def spec = suite("ZRuntimeConfig")(
      test("get decodes the request's Nuxt runtime config") {
        for text <- configured(Call(record = Some(record(valid))))
        yield assertTrue(text == "/api Secret(<redacted>)")
      },
      test("decode failures name the path and never the value") {
        val wrong = js.JSON.parse("{\"database\":{\"url\":12345678},\"apiBase\":\"/api\"}")
        for text <- configured(Call(record = Some(record(wrong))))
        yield assertTrue(
          text == "ConfigError: Nuxt runtime config does not match the expected shape at root.database.url",
          !text.contains("12345678"),
        )
      },
      test("a config read that throws is a ConfigError without its message") {
        for text <- configured(Call(record = Some(record(throw IllegalStateException("hunter2")))))
        yield assertTrue(text == "ConfigError: h34s could not read the Nuxt runtime config")
      },
      test("without the h34s Nitro plugin, config access fails with ConfigError") {
        for
          missing <- configured(Call())
          outdated <- configured(Call(record = Some(js.Dynamic.literal(development = true).asInstanceOf[js.Object])))
        yield assertTrue(
          missing == "ConfigError: h34s Nitro plugin is not installed",
          outdated == "ConfigError: h34s Nitro plugin is not installed",
        )
      },
      test("an unhandled ConfigError is an opaque 500") {
        val call = Call(record = Some(record(js.Dynamic.literal())))
        for body <- call.invoke(ZHandler.define(request => ZRuntimeConfig.get[AppConfig](request).map(_.apiBase)))
        yield assertTrue(call.response.status == 500, wire(body) == opaque)
      },
      test("load reads the startup config") {
        for
          _ <- ZIO.succeed(NitroGlobal.install(record(valid)))
          loaded <- ZRuntimeConfig.load[AppConfig].either.ensuring(ZIO.succeed(NitroGlobal.remove()))
          missing <- ZRuntimeConfig.load[AppConfig].either
        yield assertTrue(
          loaded.map(_.apiBase) == Right("/api"),
          missing.left.map(_.message) == Left("h34s Nitro plugin is not installed"),
        )
      },
    ) @@ TestAspect.sequential
end ZRuntimeConfigSpec
