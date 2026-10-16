# Kyo handlers in Nuxt

The Kyo integration runs ordinary Kyo programs on Kyo's own runtime. Install `"dev.yuri4n" %%% "kyo-h3-ext" % "0.1.0-alpha.1"` from the published private Maven release; see [releases.md](releases.md) for registry access and setup. The module depends on native h3 bindings and Kyo, without ZIO. The optional demo in `examples/nuxt/` mounts both APIs in one Nuxt application; each Scala application module has its own dependency graph.

## Run the example

Use the development toolchain from [development](development.md). Kyo 1.0.0-RC7 requires Java 25 for its build-time macros and Scala 3.9.0, selected for Kyo modules and `kyoExample`. Existing ZIO modules keep Scala 3.5.2; the shared Scala.js linker is 1.22.0.

```sh
pnpm install --frozen-lockfile
sbt 'nuxtsvr/fastLinkJS' 'kyoExample/fastLinkJS'
pnpm dev
```

Then request the Kyo routes; no database is required:

```sh
curl --fail http://127.0.0.1:3000/api/kyo/greeting
curl --fail -H 'Content-Type: application/json' \
  --data '{"name":"Scala"}' http://127.0.0.1:3000/api/kyo/greeting
node scripts/kyo-smoke.mjs
```

The responses are `{"message":"Hello, Kyo!"}` and `{"message":"Hello, Scala!"}`. Run `kyoExample/fastLinkJS` again after changing Kyo application source; Nitro watches `examples/nuxt/server/.scala-kyo`. Nitro loads the linked ES modules directly in Node and replaces its worker after relinking. `pnpm dev` enables Node source maps automatically; no heap override is needed. The application pins runtime `h3` 1.15.4 for those native imports.

In the prepared cloud environment, run `source /workspace/tooling/activate-kyo.sh` before sbt to select the separately installed Java 25. The existing Java 21 activation remains available for ZIO-only work.

The cloud launcher documented in [development](development.md) points at the prepared checkout. When using a separate worktree, adjust its working directory before running it. Normal sbt users run the commands above from their checkout.

## Choose locale data in the application

Kyo RC7 brings a full international currency database. Its generated Scala.js data adds roughly 40 MB to the development bundle. This greeting example excludes `io.github.cquiroz:locales-full-currencies-db_sjs1_3` in `kyoExample`, using scala-java-locales' supported built-in English/root formatting fallback. It does not include currency metadata.

The reusable `kyoH3Ext` module retains Kyo's upstream dependency graph. Applications needing international locale or currency data can retain the full database or select a custom database through scala-java-locales. This is application build policy; it does not restrict Unicode request or response values.

## Keep endpoints small

Define `Greetings` in your application and write the `AppHandler` wrapper shown below before using these endpoints. Both names belong to the example. The compiled source lives under `examples/nuxt/scala/kyo/src/main/scala/dev/yuri4n/kyoexample`:

```scala
val greeting = AppHandler.define:
    Env.use[Greetings](_.greet("Kyo"))

val personalizedGreeting = AppHandler.define:
    for
      input <- KHandler.readBody[GreetingRequest]
      greeting <- Env.use[Greetings](_.greet(input.name))
    yield greeting
```

`KHandler` supplies the request context, runs Kyo, converts the result, and owns the request lifecycle.

Export your handler with `JSExportTopLevel`, then create a TypeScript route file to mount it. The demo's two files under `examples/nuxt/server/api/kyo` show that setup. Route discovery and generated TypeScript types remain planned.

## NativeConverter is enough

```scala
final case class GreetingRequest(name: String) derives NativeConverter
final case class Greeting(message: String) derives NativeConverter
```

`readBody[A]` uses h3 to read the request, then converts the native JavaScript value directly. `define` converts the response in the other direction. There is no intermediate JSON string just to move values between Scala and JavaScript. HTTP serialization still happens in h3.

No Schema derivation is required. NativeConverter defines representation, not domain validation: version 0.9 accepts missing or null string fields. This greeting example deliberately has no domain constraints. Kyo Schema decoding and validation will be an optional bridge; it is not implemented in this slice. The separate optional ZIO Schema integration does not translate Kyo programs through ZIO.

## Resolve services in the application

Create an application module that constructs your services. If you want a shared handler entry point, write your own `AppHandler` to resolve `Env[Greetings]` before calling `KHandler.define`:

```scala
import dev.yuri4n.exts.h3.CommonEvHandler
import dev.yuri4n.exts.kyoh3.{HandlerEffects, KHandler}
import kyo.*
import org.getshaka.nativeconverter.NativeConverter

object AppHandler:
    def define[A: NativeConverter](
        program: => A < (HandlerEffects & Env[Greetings]),
    )(using Frame): CommonEvHandler =
      KHandler.define(Env.run(Services.greetings)(program))
```

Define `Services.greetings` from your `GreetingConfig` and `Greetings.live(config)` outside the endpoints. These example values are immutable and need no cleanup. Adapt the services and provisioning to your application. Use `KHandler.define` directly when an application wrapper adds no useful composition.

`HandlerEffects` covers `Async`, `Scope`, `Abort[Throwable]`, and `Env[KH3Event]`. `KHandler.getEvent` reads the HTTP context. `KHandler.setResponseStatus` changes the status. A Unit overload returns JavaScript `undefined` without asking the application for a converter.

## Errors and development diagnostics

A typed `Abort.fail` with `SafeError` from `dev.yuri4n.exts.kyoh3` exposes an approved `message` and `status` (400 by default). Use `Abort.fail` for a typed failure, or `KHandler.failWith(status, message)` for an explicit safe response. Both return an `errors` array matching the ZIO transport contract.

Unexpected exceptions, panics, and response conversion failures return status 500 with `{"errors":["Internal server error"]}`. Server diagnostics keep the original exception. Nuxt's existing development plugin enables `debug.cause` only when the server context contains boolean `true`; request headers and query values do not enable it.

Kyo and ZIO keep their own trace formats. [Development source maps](debugging.md) map generated JavaScript back to project Scala sources for both output directories.

## Use an existing Cats Effect library

Keep native Kyo APIs in your Kyo services. When you need an existing Cats Effect library, use the optional development [`kyo-cats-ext` bridge](kyo-cats.md) to run a complete `IO`/`Resource.use` program with cancellation and cleanup tied to the Kyo scope. It is not in the published alpha.1, and it is independent of h3, ZIO, and Skunk. Do not add a Cats bridge merely to select a database for a new Kyo service; evaluate Kyo SQL directly.

## Resource lifetime

The handler evaluates its by-name program once per request and provides a fresh HTTP context. Kyo `Scope` owns request resources. Completion and program failure wait for cleanup. When the Node response closes before completion, the integration interrupts the request worker and waits for scoped cleanup before settling the handler Promise. An already disconnected request does not start the application program.

Kyo 1.0.0-RC7 logs and suppresses errors from `Scope.ensure` finalizers. Such errors do not replace an otherwise successful or safe HTTP response. If an operation must affect the response, perform it in the main program before completion; use scope finalizers for cleanup. This differs from the current ZIO boundary's finalizer-failure response behavior.

`Env.run` supplies services; it does not establish application startup/shutdown ownership or a database pool. The example does not provide a Kyo Skunk adapter. The first slice also does not implement Kyo streaming: returning a stream value alone cannot keep resources alive until consumption finishes.

## Check the integration

```sh
sbt 'kyoH3Ext/test' 'zioH3Ext/test' 'kyoExample/fastLinkJS' 'nuxtsvr/fastLinkJS'
node --test scripts/scala-source-maps.test.mjs scripts/scala-dev-modules.test.mjs
node scripts/kyo-smoke.mjs
```

The smoke script needs the running Nuxt application. The Scala suite checks the Kyo boundary independently of ZIO, including native conversion, errors, application services, and resource lifetimes. The ZIO suite checks the shared linker update against the existing integration.

Earlier verification in the prepared Linux x86_64 checkout passed all 17 Kyo tests and 31 ZIO regression tests. Both applications compiled and development-linked under Java 25. Live Nuxt requests passed GET/POST Kyo smoke checks, and the existing ZIO `/api/test` route returned `abc`. The example’s locale configuration reduced its linked development JavaScript from 44 MiB to 5.3 MiB. All 13 development-tool checks passed. A real development exception mapped to `Greetings.scala`, and changing an application service configuration then relinking updated its HTTP response without restarting Nuxt. These direct-module development checks used the default Node heap. The separate composing loader also retains original Scala source content when bundling.

This profile records local development evidence. The alpha release workflow is configured to check independent ZIO and Kyo Maven consumers; its result must be checked separately from these earlier tests. `pnpm build` now links both example applications for production before building Nuxt. Nix locks, NixOS validation, application packaging, Kyo streams, Kyo Schema, and a Kyo database example require separate work.
