# @yuri4n/h34s-nuxt

Shared Scala.js build CLI and Nuxt integration for h34s. It supplies build ordering, output checks, route collection and registration, generated TypeScript declarations for typed `$fetch`, development loading, source maps, and the Nuxt-controlled error diagnostics plugin. Applications keep their sbt builds, models, services, and database migrations.

The npm package has its own `npm-v<version>` releases. This version, `0.2.0-alpha.1`, has the release tag [`npm-v0.2.0-alpha.1`](https://github.com/yuri4n/h34s/releases/tag/npm-v0.2.0-alpha.1) and matches the `0.2.0-alpha.1` Scala libraries; the [`npm-v0.1.0-alpha.1` release](https://github.com/yuri4n/h34s/releases/tag/npm-v0.1.0-alpha.1) matches `0.1.0-alpha.1`. Check the release before installing from the registry; an unpublished source checkout can be reviewed as a packed tarball. Maven `v*` releases publish the Scala libraries separately.

Requires Node >=22.18, Nuxt 4.3.1 or 4.4.2, and sbt/Java on the build machine. These Nuxt versions are explicit compatibility profiles. Runtime Scala dependencies are installed through Maven; installing this package does not add ZIO, Kyo, or Schema to the Scala classpath.

## Configure

Install as a dev dependency. Private registry installs use `https://npm.pkg.github.com` for the `@yuri4n` scope and a classic PAT with `read:packages` plus private package access. Never commit a token value. See the repository's `docs/npm-tooling.md` guide for authentication and local tarball installation.

Create `h34s.config.mjs` at the application root:

```js
import { defineConfig } from '@yuri4n/h34s-nuxt/build'

export default defineConfig({
  modules: [{
    project: 'server',
    output: 'server/.scala',
  }],
})
```

`project` is an sbt project ID; omit it for the root project. `entries` (for example `['notes-get.js']`) is optional: list the linked files that you mount with your own route files. Without it, the output must contain at least one h34s handler. Configure both `fastLinkJS` and `fullLinkJS` in `build.sbt` to emit ES modules into `output`, with development source maps enabled. The package does not change those Scala settings. Keep only generated files in `output`: a production build clears its previous contents.

Enable the Nuxt module:

```ts
export default defineNuxtConfig({
  modules: ['@yuri4n/h34s-nuxt'],
})
```

The module mounts the routes that your Scala handlers declare with `.at(Route...)`: it reads the route manifest (`h34s-routes.json`) that the CLI writes into each output and registers each route as a lazy Nitro handler. In development it reloads Nitro's routes when the manifest changes. It also registers the h34s Nitro plugin (`runtime/h34s.mjs`). The Scala APIs read Nitro through it: `RuntimeConfig` and `ZRuntimeConfig` (Nuxt `runtimeConfig`), `KHubKv.mount` and `ZHubKv.mount` (Nitro storage), and `Shared` and `ZShared` (application resources, released by Nitro's `close` hook; the Node server preset runs it on `SIGTERM` and `SIGINT` unless you change `NITRO_SHUTDOWN_*`). Without the module, those calls fail with `ConfigError("h34s Nitro plugin is not installed")`, and the handler answers an opaque 500.

Remove copied development loaders, source-map hooks, and the `h34s-development` plugin when switching to this module. It installs the diagnostics flags automatically. Detailed errors appear only in Nuxt development mode or when debug mode is on: `NUXT_H34S_DEBUG=true` at runtime, or `runtimeConfig: { h34s: { debug: true } }` in `nuxt.config`. Nuxt's own `debug` option does not turn it on. Requests can never turn them on. Keep debug mode off in production: it sends error causes and stack text to clients.

## Run

```json
{
  "scripts": {
    "scala:fast": "h34s-scala dev",
    "scala:watch": "h34s-scala watch",
    "scala:full": "h34s-scala build",
    "dev": "node --enable-source-maps node_modules/nuxt/bin/nuxt.mjs dev",
    "dev:scala": "pnpm run scala:fast && pnpm run dev",
    "build": "pnpm run scala:full && nuxt build"
  }
}
```

`dev` runs `fastLinkJS`; `watch` runs continuous linking. Start Nuxt after the first link and keep the watcher in a second terminal. Nitro reloads its worker when generated output changes.

After each link, the CLI loads the linked modules in a separate Node process and writes the route manifest and one `.d.ts` declaration per handler module into the output. Nuxt types `$fetch` for those routes from the declarations. Keep top-level handler values free of side effects: loading runs them.

`build` clears previous output, runs `fullLinkJS`, verifies every configured entry, and writes the route manifests and declarations before succeeding. Keep this step before Nuxt production builds. The Nuxt module does not run a production link automatically.

The CLI accepts `--root <directory>` and `--config <file>`. The Nuxt module accepts `{ configFile: 'custom.mjs' }` as its module options. Point both tools at the same application config; sbt is resolved from `PATH`.

`./build` exports `defineConfig`, `loadConfig`, `buildScala`, `linkScala`, `watchScala`, `writeRoutes`, and `readRoutes`. `./rollup` exports `scalaDevModules` and `scalaSourceMaps` for custom integrations. Most Nuxt applications need only the module and CLI.

The combined h34s profile uses Node 24. Windows, Nix/NixOS, and additional deployment targets need separate validation.
