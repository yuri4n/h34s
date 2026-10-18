package dev.yuri4n.svr.app

import org.getshaka.nativeconverter.NativeConverter
import zio.test.*

object AppConfigSpec extends ZIOSpecDefault:
    def spec = suite("AppConfig")(
      test("decodes the database settings from a Nitro runtime config and keeps the password secret") {
        val runtimeConfig =
          "{\"app\":{\"baseURL\":\"/\"},\"nitro\":{},\"public\":{},\"h34s\":{\"debug\":false}," +
            "\"database\":{\"host\":\"127.0.0.1\",\"port\":5432,\"user\":\"h34s\",\"password\":\"hunter2\",\"name\":\"h34s\"}}"
        val config = summon[NativeConverter[AppConfig]].fromJson(runtimeConfig)
        assertTrue(
          config.database.host == "127.0.0.1", config.database.port == 5432, config.database.name == "h34s",
          config.database.password.reveal == "hunter2", !config.toString.contains("hunter2"),
        )
      },
    )
