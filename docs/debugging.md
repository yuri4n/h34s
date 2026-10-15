# Debug Scala handlers in Nuxt

Run Scala linking, then `pnpm dev` as described in [development.md](development.md). Nuxt's development mode controls diagnostics automatically; endpoint code does not need a debug flag.

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

`server/plugins/h34s-development.ts` supplies the mode from Nitro's `import.meta.dev`. Production compilation replaces it with `false`; requests cannot enable it. Without the plugin, the library defaults to opaque errors. Development diagnostics can contain database or configuration details, so use the development server with trusted access.

## JavaScript-to-Scala source maps

There are two mapping stages:

1. Scala.js links Scala into `server/.scala/*.js` with adjacent `.js.map` files.
2. Nitro bundles that JavaScript into its server module and composes a final map.

The development-only loader in `build/scala-source-maps.mjs` supplies Scala.js's maps to Nitro before JavaScript transformation. Without this step, Nitro's map stops at the generated JavaScript. The loader normalizes local file URLs and embeds project Scala sources, so the final map can resolve positions to `.scala` files and lines. It does not download dependency sources; remote URLs and unavailable dependency sources may remain unresolved in a debugger.

`fastLinkJS` explicitly enables source maps. The Nuxt development hook enables Nitro maps and includes source content only in development. It leaves production source-map settings untouched; production packaging is still a separate, unvalidated slice.

The installed Nuxt CLI enables Node source-map support for its development workers. When launching a custom Node entry point directly, use `node --enable-source-maps <entry.mjs>` to map stack traces. Source maps locate code; ZIO's trace additionally identifies effect boundaries that a JavaScript call stack may not retain.

## Interactive debugging

Start Nuxt with its inspector option:

```sh
NODE_OPTIONS=--max-old-space-size=8192 pnpm dev --inspect
```

Attach a Node debugger using the inspector address printed by Nuxt, then look for the original Scala file in Sources. Server handlers execute in Node, so use its inspector session. Node can use the composed maps for source locations and breakpoints; Scala expressions, Scala collection inspection, and a full JVM-style debugger are not provided by source maps.

After editing Scala, rerun `nuxtsvr/fastLinkJS` or keep its sbt watch task running. Nuxt watches the generated JavaScript and maps. Wait for both builds before inspecting a new failure; stale linked code cannot match edited Scala source reliably.

## Checks

```sh
node --test scripts/scala-source-maps.test.mjs
sbt 'zioH3Ext/test' 'nuxtsvr/fastLinkJS'
```

The source-map checks cover local sources, source roots, remote sources, and the development-only Nuxt hook. Handler tests cover development causes, opaque production responses, safe errors, and cleanup defects. A composed Scala.js → esbuild → Rollup map has been checked with Node stack remapping. A real Nuxt database failure also returned a stack frame mapped to `notes/Notes.scala:23`; the final Nitro map includes project Scala source content. Interactive breakpoint stepping is not yet verified.

## Kyo requests

The Kyo integration uses the same Nuxt development flag and `debug.cause` field. Its diagnostics contain the original exception, nested causes, and Kyo traces rather than a ZIO Cause. Only typed `Abort.fail` values extending Kyo's `SafeError` are approved public messages; thrown defects remain opaque outside development.

The Kyo example links into `server/.scala-kyo`. The Nuxt source-map plugin accepts that directory alongside `server/.scala`, and embeds local project Scala source from either integration. Run `kyoExample/fastLinkJS` after editing Kyo code. See [Kyo handlers](kyo.md) for the compiler profile and runnable routes.
