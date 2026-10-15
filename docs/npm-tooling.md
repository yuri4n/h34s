# Share Nuxt build tooling through npm

`@yuri4n/h34s-nuxt` owns the reusable JavaScript build and development integration. Applications configure their Scala.js modules once, then use its `h34s-scala` CLI and Nuxt module. They do not copy build scripts, source-map loaders, or the development-error plugin.

**Publication status:** version `0.1.0-alpha.1` is prepared in `packages/nuxt`; its npm release is pending. The existing Maven release `v0.1.0-alpha.1` does not contain or publish this npm package. The registry installation below applies after the separate npm release succeeds. During review, use a packed tarball from the source checkout.

## Install the tooling

The package is a build-time dependency. Keep `h3` as an application runtime dependency and Scala libraries as sbt/Maven dependencies; [releases.md](releases.md) documents the latter.

After npm publication, configure the private GitHub npm registry in the application's `.npmrc`:

```ini
@yuri4n:registry=https://npm.pkg.github.com
//npm.pkg.github.com/:_authToken=${GITHUB_PACKAGES_TOKEN}
```

Provide `GITHUB_PACKAGES_TOKEN` from your shell or secret manager using a classic PAT with `read:packages` and access to the private package. Commit only the environment-variable placeholder. This npm scope mapping does not configure sbt's Maven resolver.

```sh
pnpm add -D @yuri4n/h34s-nuxt@0.1.0-alpha.1
pnpm add h3@1.15.4
```

For source review before publication, run this from the h34s checkout:

```sh
npm pack ./packages/nuxt --pack-destination /tmp
```

Then install the resulting tarball in a disposable consumer:

```sh
pnpm add -D /tmp/yuri4n-h34s-nuxt-0.1.0-alpha.1.tgz
```

The repository example uses the local package. A packed-tarball check verifies that consumers receive the runtime plugin and helpers without referencing files outside the package. A local tarball path is a review setup; use the pinned registry dependency for an independently installable application after publication.

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

The npm package requires Node 22.18 or later and Nuxt 4.3.1 or later within Nuxt 4. Use the compiler, linker, and JDK versions from the [toolchain profile](releases.md#toolchain); the combined repository profile uses Node 24. The package does not install Java or sbt.

## Add the Nuxt module and scripts

In `nuxt.config.ts`:

```ts
export default defineNuxtConfig({
  modules: ['@yuri4n/h34s-nuxt'],
})
```

The module reads the same config, watches the generated output, installs the development loader/source-map integration, and supplies the server-owned development-error flag. Remove copied versions of those hooks and the `h34s-development` plugin when adopting the module.

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

The CLI accepts `--root <directory>` and `--config <file>` for projects with a different layout. Configure an alternate file in Nuxt with `modules: [['@yuri4n/h34s-nuxt', { configFile: 'custom.mjs' }]]`. Keep the Nuxt integration and CLI pointed at the same application configuration. sbt must be available on `PATH`.

The `./build` entry point also exports `loadConfig`, `buildScala`, and `linkScala` for custom orchestration. The `./rollup` entry point exports `scalaDevModules` and `scalaSourceMaps` for custom integrations. Ordinary Nuxt applications use the module above; they do not need to wire these helpers individually.

## Keep application policy in the application

The npm package owns build orchestration, development loading, source maps, and the Nuxt development flag. It has no dependency on a particular effect API.

Applications still own `build.sbt`, Maven credentials, Scala models and services, `AppHandler`, route mounts, database migrations, secrets, and deployment policy. Keep one mount per exported route until route generation is implemented:

```ts
// server/api/notes.get.ts
export { default } from '../.scala/notes-get.js'
```

Schema remains optional. Adopting the npm package does not add ZIO, Kyo, or Schema to an application's Scala dependency graph. Nix and other future build profiles should call the same CLI pipeline. Windows, Nix/NixOS, and additional deployment targets still need their own validation.
