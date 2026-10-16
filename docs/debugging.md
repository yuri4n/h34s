# Debug Scala handlers in Nuxt

Run Scala linking, then `pnpm dev` as described in [development.md](development.md). Nuxt's development mode controls diagnostics automatically; endpoint code does not need a debug flag. The [npm Nuxt module](npm-tooling.md) owns this integration and reads the application's `h34s.config.mjs`; copying loader files or a diagnostics plugin is unnecessary.

## Error details

An unexpected failure still returns HTTP 500. In development its response includes:

```json
{
  "errors": ["Internal server error"],
  "debug": {
    "cause": "...original exception, nested causes, and ZIO trace..."
  }
}
```

Inspect `debug.cause` in the browser Network panel or the failed fetch response. The server also logs the cause. Safe errors and explicit `ZHandler.failWith` responses retain their existing status and payload.

The runtime plugin installed by `@yuri4n/h34s-nuxt` supplies the mode from Nitro's `import.meta.dev`. Production compilation replaces it with `false`; requests cannot enable it. Without the plugin, the library defaults to opaque errors. Development diagnostics can contain database or configuration details, so use the development server with trusted access.

## JavaScript-to-Scala source maps

The development profile loads linked Scala.js ES modules directly in Node:

1. `fastLinkJS` writes JavaScript and adjacent `.js.map` files to the configured application output directories. In the repository demo, these are `examples/nuxt/server/.scala` and `examples/nuxt/server/.scala-kyo`.
2. The npm module's development loader resolves configured output modules as native file imports. Nitro watches both directories and replaces its development worker after linking.
3. `pnpm dev` enables Node source maps before loading the application. Node uses the adjacent maps to resolve stack frames to original Scala files and lines.

This avoids rebundling the large linked modules on each edit. A fresh Nitro worker also gets a fresh Node module cache. Keep the JavaScript and map files together, and finish linking before making a request. The development resolver and directory watching are enabled only under Nuxt’s development flag; production uses the application's explicit Scala link followed by the Nuxt build.

The package also retains the source-map helper for composing Scala.js maps through Nitro/Rollup. It normalizes local file URLs and embeds project Scala sources. It does not download dependency sources. The default direct-module profile uses Node’s adjacent maps and local Scala source files instead.

When launching a custom Node entry point directly, use `node --enable-source-maps <entry.mjs>`. Source maps locate code; ZIO and Kyo traces additionally identify effect boundaries that a JavaScript call stack may not retain.

## Interactive debugging

Start Nuxt with its inspector option:

```sh
pnpm dev --inspect
```

Attach a Node debugger using the inspector address printed by Nuxt, then look for the original Scala file in Sources. Server handlers execute in Node, so use its inspector session. Node can use the Scala.js maps for source locations and breakpoints; Scala expressions, Scala collection inspection, and a full JVM-style debugger are not provided by source maps.

After editing Scala, run `pnpm run scala:fast` or keep `pnpm run scala:watch` running. Nuxt watches the generated JavaScript and maps. Wait for linking and worker reload before inspecting a new failure; stale linked code cannot match edited Scala source reliably.

## Checks

```sh
node --test scripts/scala-source-maps.test.mjs scripts/scala-dev-modules.test.mjs
sbt 'zioH3Ext/test' 'nuxtsvr/fastLinkJS'
```

All 13 development-tool checks pass. They cover resolver boundaries, actual Rollup-to-Node execution, original Scala source locations, map composition, and development-only configuration. A real Kyo exception in Nuxt mapped to `Greetings.scala:18` during a temporary probe, and a service configuration edit followed by relinking changed the live response without restarting Nuxt. The probe was removed afterward. Interactive breakpoint stepping is not yet verified.

Handler tests cover development causes, opaque production responses, safe errors, and cleanup defects. The earlier bundled workflow also passed a composed Scala.js → esbuild → Rollup map check and a Nuxt database-failure stack-frame check. Those results do not establish production packaging support.

## Kyo requests

The Kyo integration uses the same Nuxt development flag and `debug.cause` field. Its diagnostics contain the original exception, nested causes, and Kyo traces rather than a ZIO Cause. Only typed `Abort.fail` values extending Kyo's `SafeError` are approved public messages; thrown defects remain opaque outside development.

The Kyo example links into `examples/nuxt/server/.scala-kyo`. The development resolver and worker watcher accept that directory alongside the demo's `server/.scala`; Node uses each integration’s adjacent Scala.js maps. Run `kyoExample/fastLinkJS` after editing Kyo code. See [Kyo handlers](kyo.md) for the compiler profile and runnable routes.
