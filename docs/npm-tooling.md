# Share Nuxt build tooling through npm

`@yuri4n/h34s-nuxt` owns the reusable JavaScript build and development integration. Applications configure their Scala.js modules once, then use its `h34s-scala` CLI and Nuxt module. They do not copy build scripts, source-map loaders, or Nitro plugins.

**[`@yuri4n/h34s-nuxt@0.2.0-alpha.1` is published](https://github.com/yuri4n/h34s/releases/tag/npm-v0.2.0-alpha.1)** in the private GitHub npm registry. Its `npm-v0.2.0-alpha.1` tag is separate from the Maven [`v0.2.0-alpha.1`](https://github.com/yuri4n/h34s/releases/tag/v0.2.0-alpha.1) release; both tags point at the same commit. The older [`@yuri4n/h34s-nuxt@0.1.0-alpha.1`](https://github.com/yuri4n/h34s/releases/tag/npm-v0.1.0-alpha.1) stays published for the `0.1.0-alpha.1` libraries.

**Use the package version that matches your Scala libraries.** Install `@yuri4n/h34s-nuxt@0.2.0-alpha.1` with the `0.2.0-alpha.1` Scala libraries. The Kyo core reads runtime config, storage and the close hook from the package's new Nitro plugin. The `0.1.0-alpha.1` package only supplies the development flag, so with it `RuntimeConfig`, `KHubKv.mount` and their ZIO versions fail with `ConfigError("h34s Nitro plugin is not installed")`, and `Shared` resources are not released when Nitro closes.

## Install the tooling

The package is a build-time dependency. Keep `h3` as an application runtime dependency and Scala libraries as sbt/Maven dependencies; [releases.md](releases.md) documents the latter.

Configure the private GitHub npm registry in the application's `.npmrc`:

```ini
@yuri4n:registry=https://npm.pkg.github.com
//npm.pkg.github.com/:_authToken=${GITHUB_PACKAGES_TOKEN}
```

Provide `GITHUB_PACKAGES_TOKEN` from your shell or secret manager using a classic PAT with `read:packages` and access to the private package. Commit only the environment-variable placeholder. This npm scope mapping does not configure sbt's Maven resolver.

In GitHub Actions, save the token as `PACKAGES_READ_TOKEN` and map `${{ secrets.PACKAGES_READ_TOKEN }}` into the `GITHUB_PACKAGES_TOKEN` process environment variable. Save the username as `PACKAGES_USER` when the same workflow also resolves Maven artifacts. GitHub reserves `GITHUB_` for saved secret and variable names.

Install the package and h3 with the version of your Scala libraries:

```sh
pnpm add -D @yuri4n/h34s-nuxt@0.2.0-alpha.1
pnpm add h3@1.15.4
```

With the older `0.1.0-alpha.1` Scala libraries, install the matching package instead: `pnpm add -D @yuri4n/h34s-nuxt@0.1.0-alpha.1`.

For local source review, run this from the h34s checkout:

```sh
npm pack ./packages/nuxt --pack-destination /tmp
```

Then install the resulting tarball in a disposable consumer:

```sh
pnpm add -D /tmp/yuri4n-h34s-nuxt-0.2.0-alpha.1.tgz
```

The repository example uses the local package. A packed-tarball check verifies that consumers receive the runtime plugin and helpers without referencing files outside the package. A local tarball path is a review setup; use the pinned registry dependency for an independently installable application.

Run `pnpm test:package` after installing the repository dependencies to verify the archive in an isolated consumer and a real Nuxt development/production server. The HTTP check covers development diagnostics, Scala source maps, relinking, and a production bundle that runs without the original generated files.

For `0.2.0-alpha.1`, the [successful npm release workflow](https://github.com/yuri4n/h34s/actions/runs/39144809255) downloaded the published archive with registry authentication, compared its bytes with the tested tarball, and installed that download offline in a clean consumer. It checked the CLI and all three JavaScript entry points without installing Nuxt as a peer. The [release](https://github.com/yuri4n/h34s/releases/tag/npm-v0.2.0-alpha.1) attaches the tarball and `registry.json`, which records its stable registry URL and integrity. The [`0.1.0-alpha.1` workflow](https://github.com/yuri4n/h34s/actions/runs/38937318206) ran the same checks for that version.

Nuxt 4.3.1 passes the packed-package development and production HTTP checks in CI, including the `0.2.0-alpha.1` release workflow. Nuxt 4.4.2 passed the same checks locally on Linux x86_64 with Node 24.19.0 for `0.1.0-alpha.1`; h34s's own checks have not repeated that profile for `0.2.0-alpha.1`. Package-level tests also cover Node 22.18.0 and 24. These results establish those explicit profiles, not every Nuxt 4.x version or a Nix/NixOS build.

## Configure the Scala.js outputs

Create `h34s.config.mjs` beside the application's `package.json`:

```js
import { defineConfig } from '@yuri4n/h34s-nuxt/build'

export default defineConfig({
  modules: [
    {
      project: 'server',
      output: 'server/.scala',
      entries: ['notes-get.js', 'notes-post.js'],
    },
  ],
})
```

`project` is the sbt project ID, not a filesystem directory. Omit it for a root-project task such as `fullLinkJS`. `output` is the generated directory relative to the application root. `entries` lists the JavaScript files mounted by your routes; the CLI checks that linking produced each file. Add another module for an independent Kyo application if needed. Each module needs its own generated output directory.

Keep only generated files in these directories: the production command removes previous output before linking. Do not point `output` at a source directory or an application root.

The package does not change `build.sbt`. Configure both linking modes to emit into the same directory and enable development source maps. For an sbt server project, these settings are relative to the root sbt build:

```scala
import org.scalajs.linker.interface.ModuleKind

// Inside the Scala.js server project's .settings(...):
scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
Compile / fastLinkJS / scalaJSLinkerConfig ~= (_.withSourceMap(true)),
Compile / fastLinkJS / scalaJSLinkerOutputDirectory :=
  (LocalRootProject / baseDirectory).value / "server" / ".scala",
Compile / fullLinkJS / scalaJSLinkerOutputDirectory :=
  (LocalRootProject / baseDirectory).value / "server" / ".scala",
```

The npm package requires Node 22.18 or later and Nuxt 4.3.1 or 4.4.2. These are explicit compatibility profiles; other Nuxt versions need validation. Use the compiler, linker, and JDK versions from the [toolchain profile](releases.md#toolchain); the combined repository profile uses Node 24. The package does not install Java or sbt.

## Add the Nuxt module and scripts

In `nuxt.config.ts`:

```ts
export default defineNuxtConfig({
  modules: ['@yuri4n/h34s-nuxt'],
})
```

The module reads the same config, watches the generated output, installs the development loader/source-map integration, and registers the h34s Nitro plugin (`runtime/h34s.mjs`). The plugin gives your Scala handlers, through internal records that requests cannot change:

| Record entry | Used by |
| --- | --- |
| `development` (Nitro's `import.meta.dev`) | Development diagnostics |
| `debug` (`runtimeConfig.h34s.debug`, read once at startup) | Debug diagnostics; see [debugging](debugging.md) |
| `runtimeConfig` (per request and at startup) | `RuntimeConfig` / `ZRuntimeConfig`; see [configuration](configuration.md) |
| `storage(base)` (Nitro `useStorage`) | `KHubKv.mount` / `ZHubKv.mount`; see [Nitro storage](nuxthub.md) |
| `onClose` (Nitro's `close` hook) | `Shared` / `ZShared` release; see [lifetimes](lifetimes.md) |

The module also declares `runtimeConfig.h34s.debug` with the value of Nuxt's `debug` option, so `NUXT_H34S_DEBUG=true` can turn on diagnostics at runtime. A value your `nuxt.config.ts` already sets wins. Keep it off in production.

Remove copied versions of those hooks and the old `h34s-development` plugin when adopting the module. If you listed `runtime/development.mjs` in `nitro.plugins` yourself, remove that entry: the module registers the new plugin, and the old file only re-exports it for one release.

Add these scripts to `package.json`:

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

Run `pnpm dev:scala` for the first link and Nuxt startup. Keep `pnpm scala:watch` in a second terminal while editing Scala. The watcher runs sbt's continuous linking task; it does not start Nuxt. Wait for linking and Nitro's worker restart before requesting edited code.

For production, `pnpm build` clears each generated output directory, runs `fullLinkJS`, verifies the configured entry files, and only then starts the Nuxt build. A linker failure or missing entry stops the command. The Nuxt module alone does not run a production link; keep that ordering in the application build script.

Large linked outputs make Nitro's production bundling memory-hungry. The repository demo, which links a Kyo and a ZIO application, needed `NODE_OPTIONS=--max-old-space-size=8192` for `nuxt build` on this branch. If your build stops with a JavaScript heap error, raise the limit the same way.

The CLI accepts `--root <directory>` and `--config <file>` for projects with a different layout. Configure an alternate file in Nuxt with `modules: [['@yuri4n/h34s-nuxt', { configFile: 'custom.mjs' }]]`. Keep the Nuxt integration and CLI pointed at the same application configuration. sbt must be available on `PATH`.

The `./build` entry point also exports `loadConfig`, `buildScala`, and `linkScala` for custom orchestration. The `./rollup` entry point exports `scalaDevModules` and `scalaSourceMaps` for custom integrations. Ordinary Nuxt applications use the module above; they do not need to wire these helpers individually.

## Keep application policy in the application

The npm package owns build orchestration, development loading, source maps, and the Nitro plugin. It has no dependency on a particular effect API.

Configure your own `build.sbt` and Maven credentials. Define models and services in your application, compose their environment in an optional `AppHandler` wrapper, and configure any database resources, migrations, secrets, and deployment policy there. Create one mount per exported route until route generation is implemented:

```ts
// server/api/notes.get.ts
export { default } from '../.scala/notes-get.js'
```

Schema remains optional. Adopting the npm package does not add ZIO, Kyo, or Schema to an application's Scala dependency graph. Nix and other future build profiles should call the same CLI pipeline. Windows, Nix/NixOS, and additional deployment targets still need their own validation.
