# Kyo handlers in Nuxt

This unpublished integration runs ordinary Kyo programs on Kyo's own runtime. The reusable `kyoH3Ext` module depends on native h3 bindings and Kyo, without ZIO. The combined demonstration mounts both APIs in the same Nuxt application; each application module has its own dependency graph.

## Run the example

Use the development toolchain from [development](development.md). Kyo 1.0.0-RC7 requires Java 25 for its build-time macros and Scala 3.9.0, selected only for `kyoH3Ext` and `kyoExample`. Existing ZIO modules keep Scala 3.5.2; the shared Scala.js linker is 1.22.0.

```sh
pnpm install --frozen-lockfile
sbt 'nuxtsvr/fastLinkJS' 'kyoExample/fastLinkJS'
NODE_OPTIONS=--max-old-space-size=8192 pnpm dev
```

Then request the Kyo routes; no database is required:

```sh
curl --fail http://127.0.0.1:3000/api/kyo/greeting
curl --fail -H 'Content-Type: application/json' \
  --data '{"name":"Scala"}' http://127.0.0.1:3000/api/kyo/greeting
node scripts/kyo-smoke.mjs
```

The responses are `{"message":"Hello, Kyo!"}` and `{"message":"Hello, Scala!"}`. Run `kyoExample/fastLinkJS` again after changing Kyo application source; Nitro watches `server/.scala-kyo`. The development source-map integration accepts both linked output directories.

In the prepared cloud environment, run `source /workspace/tooling/activate-kyo.sh` before sbt to select the separately installed Java 25. The existing Java 21 activation remains available for ZIO-only work.

The cloud launcher documented in [development](development.md) points at the prepared checkout. When using a separate worktree, adjust its working directory before running it. Normal sbt users run the commands above from their checkout.

## Choose locale data in the application

Kyo RC7 brings a full international currency database. Its generated Scala.js data adds roughly 40 MB to the development bundle. This greeting example excludes `io.github.cquiroz:locales-full-currencies-db_sjs1_3` in `kyoExample`, using scala-java-locales' supported built-in English/root formatting fallback. It does not include currency metadata.

The reusable `kyoH3Ext` module retains Kyo's upstream dependency graph. Applications needing international locale or currency data can retain the full database or select a custom database through scala-java-locales. This is application build policy; it does not restrict Unicode request or response values.

## Keep endpoints small

The compiled examples live under `scala-modules/kyo-example/src/main/scala/dev/yuri4n/kyoexample`:

```scala
val greeting = AppHandler.define:
    Env.use[Greetings](_.greet("Kyo"))

val personalizedGreeting = AppHandler.define:
    for
      input <- KHandler.readBody[GreetingRequest]
      greeting <- Env.use[Greetings](_.greet(input.name))
    yield greeting
```

`Greetings` is an ordinary application service. `KHandler` supplies the request context, runs Kyo, converts the result, and owns the request lifecycle. Endpoint code does not create a runtime or a JavaScript Promise.

The full source includes `JSExportTopLevel` annotations. The two TypeScript files under `server/api/kyo` mount these linked exports. Route discovery and generated TypeScript types remain planned.

## NativeConverter is enough

```scala
final case class GreetingRequest(name: String) derives NativeConverter
final case class Greeting(message: String) derives NativeConverter
```

`readBody[A]` uses h3 to read the request, then converts the native JavaScript value directly. `define` converts the response in the other direction. There is no intermediate JSON string just to move values between Scala and JavaScript. HTTP serialization still happens in h3.

No Schema derivation is required. NativeConverter defines representation, not domain validation: version 0.9 accepts missing or null string fields. This greeting example deliberately has no domain constraints. Kyo Schema decoding and validation will be an optional bridge; it is not implemented in this slice. The separate optional ZIO Schema integration does not translate Kyo programs through ZIO.

## Resolve services in the application

The application's `AppHandler` supplies `Greetings` before entering the library:

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

`Services` constructs a `GreetingConfig` and `Greetings.live(config)` outside the handlers. These are immutable application values; sharing them does not require cleanup. Replace these services and provisioning with your own ordinary Kyo composition.

`HandlerEffects` covers `Async`, `Scope`, `Abort[Throwable]`, and `Env[KH3Event]`. `KHandler.getEvent` reads the HTTP context. `KHandler.setResponseStatus` changes the status. A Unit overload returns JavaScript `undefined` without asking the application for a converter.

## Errors and development diagnostics

A typed `Abort.fail` with `SafeError` from `dev.yuri4n.exts.kyoh3` exposes an approved `message` and `status` (400 by default). Use `Abort.fail` for a typed failure, or `KHandler.failWith(status, message)` for an explicit safe response. Both return an `errors` array matching the ZIO transport contract.

Unexpected exceptions, panics, and response conversion failures return status 500 with `{"errors":["Internal server error"]}`. Server diagnostics keep the original exception. Nuxt's existing development plugin enables `debug.cause` only when the server context contains boolean `true`; request headers and query values do not enable it.

Kyo and ZIO keep their own trace formats. [Development source maps](debugging.md) compose generated JavaScript back to project Scala sources for both output directories.

## Resource lifetime

The handler evaluates its by-name program once per request and provides a fresh HTTP context. Kyo `Scope` owns request resources. Completion and program failure wait for cleanup. When the Node response closes before completion, the integration interrupts the request worker and waits for scoped cleanup before settling the handler Promise. An already disconnected request does not start the application program.

Kyo 1.0.0-RC7 logs and suppresses errors from `Scope.ensure` finalizers. Such errors do not replace an otherwise successful or safe HTTP response. If an operation must affect the response, perform it in the main program before completion; use scope finalizers for cleanup. This differs from the current ZIO boundary's finalizer-failure response behavior.

`Env.run` supplies services; it does not establish application startup/shutdown ownership or a database pool. The example does not provide a Kyo Skunk adapter. The first slice also does not implement Kyo streaming: returning a stream value alone cannot keep resources alive until consumption finishes.

## Check the integration

```sh
sbt 'kyoH3Ext/test' 'zioH3Ext/test' 'kyoExample/fastLinkJS' 'nuxtsvr/fastLinkJS'
node --test scripts/scala-source-maps.test.mjs
node scripts/kyo-smoke.mjs
```

The smoke script needs the running Nuxt application. The Scala suite checks the Kyo boundary independently of ZIO, including native conversion, errors, application services, and resource lifetimes. The ZIO suite checks the shared linker update against the existing integration.

This profile is development evidence, not a published support guarantee. Nix locks, NixOS validation, production packaging, fresh-consumer CI, Kyo streams, Kyo Schema, and a Kyo database example require separate work.
