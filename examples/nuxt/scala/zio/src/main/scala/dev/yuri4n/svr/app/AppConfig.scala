package dev.yuri4n.svr.app

import dev.yuri4n.exts.ntcvzioschema.{NativeSchema, secretSchema}
import dev.yuri4n.exts.zioh3.Secret
import zio.schema.{Schema, derived}

/** PostgreSQL settings from Nuxt `runtimeConfig.database` (see `nuxt.config.ts`).
  *
  * Nitro overrides each key at runtime from `NUXT_DATABASE_HOST`, `NUXT_DATABASE_PORT`,
  * `NUXT_DATABASE_USER`, `NUXT_DATABASE_PASSWORD` and `NUXT_DATABASE_NAME`. Supply the password only
  * at runtime; an empty password means none. One derivation, like every model of the example: the
  * ZIO Schema (with the bridge's `secretSchema` for the password) and the native converter.
  */
final case class DatabaseConfig(host: String, port: Int, user: String, password: Secret, name: String)
    derives Schema, NativeSchema

/** The part of the Nuxt runtime config that this example reads. Other keys are ignored. */
final case class AppConfig(database: DatabaseConfig) derives Schema, NativeSchema
