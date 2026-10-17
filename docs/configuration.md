# Configuration and secrets

Read configuration from Scala. h34s decodes Nuxt `runtimeConfig` into your own case class, so you never touch `process.env`, `useRuntimeConfig` or JavaScript objects. Nitro applies `NUXT_*` environment overrides before h34s sees the values.

**Status.** `RuntimeConfig` and `ZRuntimeConfig` are part of `0.2.0-SNAPSHOT` (not published yet). They need the h34s Nitro plugin, which the `@yuri4n/h34s-nuxt` module registers.

## 1. Declare the keys in `nuxt.config.ts`

Nuxt only overrides keys that you declare. Declare every key with a safe development default, and leave secrets empty:

```ts
export default defineNuxtConfig({
  modules: ['@yuri4n/h34s-nuxt'],
  runtimeConfig: {
    database: {
      host: '127.0.0.1',
      port: 5432,
      user: 'h34s',
      password: '',
      name: 'h34s',
    },
  },
})
```

Keys outside `runtimeConfig.public` are private: Nuxt never sends them to the browser. h34s reads private and public keys.

## 2. Define a Scala type for the part you read

```scala
import dev.yuri4n.exts.zioh3.Secret // or dev.yuri4n.exts.kyoh3.Secret
import org.getshaka.nativeconverter.NativeConverter

final case class DatabaseConfig(host: String, port: Int, user: String, password: Secret, name: String)
    derives NativeConverter

/** The part of the Nuxt runtime config that this application reads. Other keys are ignored. */
final case class AppConfig(database: DatabaseConfig) derives NativeConverter
```

This is the demo's `AppConfig.scala`. The type only needs a `NativeConverter`; a schema type works too.

## 3. Read it

| You need | Kyo | ZIO |
| --- | --- | --- |
| The config for this request | `RuntimeConfig.get[AppConfig](request)` | `ZRuntimeConfig.get[AppConfig](request)` |
| The startup config, for application resources | `RuntimeConfig.load[AppConfig]` | `ZRuntimeConfig.load[AppConfig]` |

`get` returns Nitro's per-request copy. `load` returns Nitro's shared startup copy; use it inside `Shared` or `ZShared` to build a pool once. The demo's `SessionPool.layer` does that:

```scala
val layer: ZLayer[Any, Throwable, SessionPool] =
  ZLayer.scoped(
    for
      config   <- ZRuntimeConfig.load[AppConfig]
      sessions <- pooled(config.database).toScopedZIO
    yield SessionPool(sessions),
  )
```

The Kyo types are `A < (Sync & Abort[ConfigError])`; the ZIO types are `IO[ConfigError, A]`.

## 4. Override values at runtime

Nitro maps each declared key to an environment variable: the `NUXT_` prefix, then the key path in upper snake case. For the config above:

| Key | Variable |
| --- | --- |
| `database.host` | `NUXT_DATABASE_HOST` |
| `database.port` | `NUXT_DATABASE_PORT` |
| `database.password` | `NUXT_DATABASE_PASSWORD` |

A camelCase key such as `apiToken` becomes `NUXT_API_TOKEN`. Set secrets this way, at runtime, in your shell, process manager or hosting platform.

Nitro parses each value before h34s sees it (Nitro 2.13 uses `destr`). `5432` becomes a number and `true` becomes a boolean. A secret that looks like a number, such as `12345`, therefore arrives as a number, and decoding a `Secret` field fails with a `ConfigError`. This comes from Nitro's source; h34s has no test for it. Prefer secrets that are not numeric, or check the failing path in the server log.

## When configuration is wrong

Every failure is a `ConfigError`. It is never shown to clients: an unhandled one is an opaque 500. Its message names only the failing path, never a value:

| Situation | Message |
| --- | --- |
| The h34s Nitro plugin is not installed (the module is missing) | `h34s Nitro plugin is not installed` |
| A field has the wrong type | `Nuxt runtime config does not match the expected shape at root.database.port` |
| Reading the config throws | `h34s could not read the Nuxt runtime config` |

Fix the key in `nuxt.config.ts` or the environment, then restart the server. Development mode shows the message in `debug.cause`; production logs it on the server.

A derived `NativeConverter` (version 0.9) accepts a missing or `null` value for a plain `String` field. Declare every key in `nuxt.config.ts`, or use a [schema](schema.md) type when a missing value must fail.

## Keep secrets secret

Wrap every secret in `Secret`:

- `toString` prints `Secret(<redacted>)`, so a secret in a case class does not appear in logs or diagnostics.
- `secret.reveal` returns the plain value. Call it only where the value must leave your program, such as a database client.
- `secret.matches(candidate)` compares in constant time for a given candidate length. Use it to check tokens:

```scala
final case class AdminConfig(adminToken: Secret) derives NativeConverter

def isAdmin(request: Request, config: AdminConfig): Boolean =
  request.bearerToken.exists(config.adminToken.matches)
```

Follow these rules:

- Never log a revealed secret, put it in a `SafeError` message, or return it in a response body.
- Never commit secret values in `nuxt.config.ts`, `.env` files that are part of a build, or source files. Supply them at runtime.
- Keep debug mode off in production. Debug diagnostics include exception messages, and a library can put connection details in them.

## What is tested

- `RuntimeConfig.get` and `load` decoding, path-only error messages, a throwing read, the missing plugin, and the opaque 500: Kyo `RuntimeConfigSuite` and ZIO `ZRuntimeConfigSpec`, with a fake plugin record.
- The demo's `AppConfig` decoding with a redacted password: `AppConfigSpec`.
- A production build of the demo (`nuxt build`, then the Node server, macOS x86_64): `ZRuntimeConfig.load` read the database settings, and a marker password supplied at runtime appeared neither in the server logs nor in the debug response. A separate test of each `NUXT_*` override has not been run.
