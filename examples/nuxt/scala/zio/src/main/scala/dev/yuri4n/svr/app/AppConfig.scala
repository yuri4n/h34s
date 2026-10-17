package dev.yuri4n.svr.app

import dev.yuri4n.exts.zioh3.Secret
import org.getshaka.nativeconverter.NativeConverter

/** PostgreSQL settings from Nuxt `runtimeConfig.database` (see `nuxt.config.ts`).
  *
  * Nitro overrides each key at runtime from `NUXT_DATABASE_HOST`, `NUXT_DATABASE_PORT`,
  * `NUXT_DATABASE_USER`, `NUXT_DATABASE_PASSWORD` and `NUXT_DATABASE_NAME`. Supply the password only
  * at runtime; an empty password means none.
  */
final case class DatabaseConfig(host: String, port: Int, user: String, password: Secret, name: String)
    derives NativeConverter

/** The part of the Nuxt runtime config that this example reads. Other keys are ignored. */
final case class AppConfig(database: DatabaseConfig) derives NativeConverter
