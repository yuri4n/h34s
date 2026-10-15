# @yuri4n/h34s-nuxt

Shared Scala.js build CLI and Nuxt development integration for h34s. It supplies build ordering, output checks, development loading, source maps, and the Nuxt-controlled error diagnostics plugin. Applications keep their sbt builds, route mounts, models, services, and database migrations.

**First npm publication is pending.** The prepared package version is `0.1.0-alpha.1`. The existing h34s Maven `v0.1.0-alpha.1` release does not publish this package. Review it as a packed tarball until the separate npm release succeeds.

Requires Node >=22.18, Nuxt ^4.3.1, and sbt/Java on the build machine. Runtime Scala dependencies are installed through Maven; installing this package does not add ZIO, Kyo, or Schema to the Scala classpath.

## Configure

Install as a dev dependency. Private registry installs use `https://npm.pkg.github.com` for the `@yuri4n` scope and a classic PAT with `read:packages` plus private package access. Never commit a token value. See the repository's `docs/npm-tooling.md` guide for authentication and local tarball installation.

Create `h34s.config.mjs` at the application root:

```js
import { defineConfig } from '@yuri4n/h34s-nuxt/build'

export default defineConfig({
  modules: [{
    project: 'server',
    output: 'server/.scala',
    entries: ['notes-get.js', 'notes-post.js'],
  }],
})
```

`project` is an sbt project ID; omit it for the root project. Configure both `fastLinkJS` and `fullLinkJS` in `build.sbt` to emit ES modules into `output`, with development source maps enabled. The package does not change those Scala settings. Keep only generated files in `output`: a production build clears its previous contents.

Enable the Nuxt module:

```ts
export default defineNuxtConfig({
  modules: ['@yuri4n/h34s-nuxt'],
})
```

Remove copied development loaders, source-map hooks, and the `h34s-development` plugin when switching to this module. It installs the development diagnostics flag automatically; only Nuxt development mode enables detailed errors.

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

`build` clears previous output, runs `fullLinkJS`, and verifies every configured entry before succeeding. Keep this step before Nuxt production builds. The Nuxt module does not run a production link automatically.

The CLI accepts `--root <directory>` and `--config <file>`. The Nuxt module accepts `{ configFile: 'custom.mjs' }` as its module options. Point both tools at the same application config; sbt is resolved from `PATH`.

`./build` exports `defineConfig`, `loadConfig`, `buildScala`, and `linkScala`. `./rollup` exports `scalaDevModules` and `scalaSourceMaps` for custom integrations. Most Nuxt applications need only the module and CLI.

The combined h34s profile uses Node 24. Windows, Nix/NixOS, and additional deployment targets need separate validation.
