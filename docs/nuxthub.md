# Optional NuxtHub KV bindings

This slice binds NuxtHub **0.10.8**'s generated KV API. It does not bind NuxtHub DB, Drizzle schemas, Blob storage, or Alchemy deployment resources. Applications own those integrations and their configuration.

The modules are independent:

| sbt project | API | Runtime dependencies |
| --- | --- | --- |
| `nuxtHubExt` | `HubKv`, `NativeKvSetOptions` | Scala.js only |
| `zioNuxtHubExt` | `ZHubKv` | ZIO 2.1.23 and NativeConverter 0.9.0 |
| `kyoNuxtHubExt` | `KHubKv` | Kyo 1.0.0-RC7 and NativeConverter 0.9.0 |

Neither adapter requires Schema or the other effect runtime. The native module also works without either adapter. These source projects are not published installation coordinates.

## Supply the application's KV

Enable and configure KV in the Nuxt application, then run Nuxt preparation so `@nuxthub/kv` exists. NuxtHub generates this module; the reusable h34s libraries never import it. Supply its `kv` value at the application composition boundary.

For example, an application can declare its own import:

```scala
import dev.yuri4n.exts.nuxthub.HubKv
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("@nuxthub/kv", "kv")
object ApplicationKv extends HubKv
```

This import belongs in the application, alongside its service composition. An application can also receive a native KV object from its JavaScript entry point and pass it to the adapter. The library does not create storage drivers, read credentials, or own the application's storage shutdown.

## Use ordinary effect programs

Models need only NativeConverter:

```scala
import org.getshaka.nativeconverter.NativeConverter

final case class Profile(name: String, visits: Int) derives NativeConverter
```

The ZIO adapter returns `Task` values:

```scala
import dev.yuri4n.exts.zionuxthub.ZHubKv

val cache = ZHubKv(ApplicationKv)
val update = for
  _ <- cache.set("profiles:42", Profile("Yuri", 1), ttlSeconds = Some(300))
  profile <- cache.get[Profile]("profiles:42")
yield profile // Option[Profile]
```

The Kyo adapter executes directly in Kyo:

```scala
import dev.yuri4n.exts.kyonuxthub.KHubKv
import kyo.*

val cache = KHubKv(ApplicationKv)
val update = cache.set("profiles:42", Profile("Yuri", 1)).map { _ =>
  cache.get[Profile]("profiles:42")
} // Option[Profile] < (Async & Abort[Throwable])
```

Provide the adapter through ordinary `ZLayer`/ZIO services or Kyo `Env` when the application needs dependency injection. Application `AppHandler` composition remains responsible for those services; the KV adapter does not supply an HTTP handler or an effect runtime.

Both adapters expose `get[A](key)`, `set[A](key, value, ttlSeconds = None)`, `has(key)`, `delete(key)`, and `keys(base = None)`. Storage calls and conversion are deferred until execution. Reusing an operation executes a new storage call each time. Synchronous native exceptions, rejected Promises, and conversion errors enter the effect's `Throwable` failure channel.

## Preserve the storage contract

NuxtHub's generated `kv` exposes `get`, `set`, `has`, `del`, and `keys`. The Scala `delete` method calls `del`; these bindings do not assume a raw `getItem` API.

- Missing values and stored `null` both become `None`. Use `has` when that distinction matters. Native `set(key, undefined)` deletes a key.
- NativeConverter defines the stored representation; it does not validate domain rules. Conversion uses native values directly, without a JSON text round trip in the adapter. The storage driver still serializes values as required by its backend.
- Unstorage's default serialization does not preserve every top-level string: text such as `"42"`, `"true"`, or `"null"` can deserialize into another JavaScript type. The adapter preserves that upstream behavior. Prefer model objects for typed records; strings inside DTO fields retain their type. If the converter rejects a value, the operation fails rather than treating the key as missing.
- `ttlSeconds` is forwarded as the native `{ ttl: seconds }` option. Expiration is driver-dependent. The tested memory driver and NuxtHub's fs-lite driver do not implement TTL; a successful `set` does not prove expiration support.
- Keys follow Unstorage normalization, including `/` to `:`. `keys(Some("profiles"))` selects descendants under `profiles:`; ordering is driver-dependent. This bounded API does not expose every driver-specific transaction option.
- Storage Promises have no cancellation contract here. Interrupting a waiting fiber does **not** cancel or roll back an already-started write. Design retries and writes with that behavior in mind.

## Test the adapters

Use the repository's Scala toolchain: Scala 3.5.2 for native/ZIO modules, Scala 3.9.0 for Kyo, Scala.js 1.22.0, and Java 25 for Kyo macros. Install the isolated, private JavaScript test fixture with its lockfile:

```sh
npm ci --prefix scala-modules/exts/nuxthub/test-js --ignore-scripts
sbt 'zioNuxtHubExt/test' 'kyoNuxtHubExt/test'
```

The fixture pins Unstorage **1.17.3** and constructs `createStorage({ driver: memoryDriver({}) })`, exactly as NuxtHub 0.10.8 generates for its memory driver. It does not start Nuxt or connect to a cloud service. Fault-injection tests separately check failure channels, deferred execution, and TTL forwarding. A test-only `-Dh34s.nuxthub.nodePath=/absolute/node_modules` sbt property can select an existing fixture installation.

All 21 adapter tests pass in the prepared Linux x86_64 checkout: 10 ZIO and 11 Kyo. Both include actual JavaScript failures and interruption while the underlying native operation can still complete. Resolved compile classpaths confirm that neither effect adapter depends on the other runtime.

The source contract comes from [NuxtHub 0.10.8](https://registry.npmjs.org/@nuxthub/core/-/core-0.10.8.tgz), `dist/module.mjs` and `dist/kv/runtime/kv.d.ts`, and [Unstorage 1.17.3](https://registry.npmjs.org/unstorage/-/unstorage-1.17.3.tgz). These tests do not establish AWS, Cloudflare, Redis, or production deployment support.
