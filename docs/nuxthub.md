# Nitro storage and NuxtHub KV

Use key-value storage from Scala without JavaScript. `KHubKv.mount(base)` (Kyo) and `ZHubKv.mount(base)` (ZIO) open a Nitro storage mount: the same storage that Nitro's `useStorage(base)` returns. You do not import `@nuxthub/kv` or pass a native object.

**Status.** `mount` is part of `0.2.0-SNAPSHOT` (not published yet). It replaces the `KHubKv(native)` and `ZHubKv(native)` constructors of `0.1.0-alpha.1`; see the [migration guide](migration.md).

| Module | API | Depends on |
| --- | --- | --- |
| `kyo-nuxthub-ext` | `KHubKv` (package `dev.yuri4n.exts.kyonuxthub`) | the Kyo core |
| `zio-nuxthub-ext` | `ZHubKv` (package `dev.yuri4n.exts.zionuxthub`) | `kyo-nuxthub-ext` and the ZIO adapter |

Neither module needs a schema. The h34s Nitro plugin, which the `@yuri4n/h34s-nuxt` module registers, supplies the storage.

## Choose a mount

A mount is a named part of Nitro storage with its own driver. Configure mounts in `nuxt.config.ts` with Nitro's `storage` option, or let a module such as NuxtHub add them. For example, a Redis-backed `cache` mount:

```ts
export default defineNuxtConfig({
  modules: ['@yuri4n/h34s-nuxt'],
  nitro: {
    storage: {
      cache: { driver: 'redis', url: process.env.REDIS_URL },
    },
  },
})
```

Keep connection URLs and passwords out of committed files; read them from the environment as shown. `mount("")` opens the root storage, which sees every mount.

NuxtHub: whether NuxtHub 0.10.8 exposes its KV as a Nitro storage mount has not been verified. The bindings were tested with Unstorage 1.17.3's memory driver, the driver NuxtHub 0.10.8 generates for local KV. Check the mount name in your NuxtHub setup before you rely on it.

## Open and use a mount

Models need only a `NativeConverter` (or a [schema](schema.md)):

```scala
import org.getshaka.nativeconverter.NativeConverter

final case class Profile(name: String, visits: Int) derives NativeConverter
```

Kyo:

```scala
import dev.yuri4n.exts.kyonuxthub.KHubKv
import kyo.*

val visit: Option[Profile] < (Async & Abort[Throwable]) =
  for
    cache   <- KHubKv.mount("cache")
    _       <- cache.set("profiles:42", Profile("Yuri", 1), ttlSeconds = Some(300.0))
    profile <- cache.get[Profile]("profiles:42")
  yield profile
```

ZIO:

```scala
import dev.yuri4n.exts.zionuxthub.ZHubKv
import zio.*

val visit: Task[Option[Profile]] =
  for
    cache   <- ZHubKv.mount("cache")
    _       <- cache.set("profiles:42", Profile("Yuri", 1), ttlSeconds = Some(300.0))
    profile <- cache.get[Profile]("profiles:42")
  yield profile
```

Both APIs offer `get[A](key)`, `set[A](key, value, ttlSeconds = None)`, `has(key)`, `delete(key)` and `keys(base = None)`. Kyo operations are `A < (Async & Abort[Throwable])`; ZIO operations are `Task[A]`. Each run calls the storage again.

`mount` fails with `ConfigError` when the h34s plugin is not installed (`h34s Nitro plugin is not installed`) or when the mount is not available. An unhandled `ConfigError` is an opaque 500.

## Know the storage contract

- **Missing values.** A missing key and a stored `null` are both `None`. Use `has` when the difference matters.
- **Conversion.** Values are converted directly to native values; the driver serializes them for its backend. A stored value that the converter rejects fails `get` with `StoredValueError`, a typed failure that names only the failing path (`root.count`, or a schema path), never the key or the stored value: keys are often session ids, and values can hold tokens. It is server data, so an unhandled one is an opaque 500, never a 422, even when a schema converter rejected it. It is not treated as a missing key.
- **Top-level strings.** Unstorage's default serialization can turn a stored top-level string such as `"42"`, `"true"` or `"null"` into another JavaScript type (a `Secret("12345")` comes back as a number and fails to decode). Store objects (a case class) for typed records.
- **TTL.** `ttlSeconds` is forwarded as `{ ttl: seconds }`. Expiry depends on the driver. The memory driver and NuxtHub's fs-lite driver ignore it, so a successful `set` does not prove that a key expires.
- **Keys.** Keys follow Unstorage normalization: `/` becomes `:`. `keys(Some("profiles"))` lists keys under `profiles:`. The order depends on the driver.
- **Cancellation.** Storage calls cannot be cancelled. Interrupting a waiting fiber does not cancel or roll back a write that already started.

## Invalidate cached data

h34s adds no cache abstraction. Common patterns with these operations:

- **Delete on write.** After you change the source data, `delete` the cached key in the same handler.
- **Generation keys.** Store a generation number under one key and include it in every cached key (`catalogue:v7:page:1`). Increment it to invalidate everything at once; old keys expire through the driver's TTL.
- **Short TTLs.** For data that may be slightly stale, set `ttlSeconds` and accept the delay. Check that your driver supports TTL first.

## What is tested

`KHubKvSuite` (15 tests) and `ZHubKvSpec` (14 tests) on macOS x86_64 with Node 24.19.0: `mount` through a fake h34s startup record, `ConfigError` without the plugin or the mount, round trips with Unstorage 1.17.3's memory driver, `false` and `0` as stored values, `null` and `undefined` as `None`, lazy operations, exact TTL forwarding, JavaScript errors and rejected Promises in the failure channel, value-free `StoredValueError`s for shape and schema mismatches (an opaque 500 in a handler), a `SafeError` from an encoder that stays unapproved, upstream string deserialization, and interruption during a write. No test starts Nuxt, NuxtHub or a cloud service.
