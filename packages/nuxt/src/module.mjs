import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { loadConfig } from './build.mjs'
import { scalaDevModules, scalaSourceMaps } from './rollup.mjs'

// A standard Nuxt module function. No runtime dependency on an effect library,
// Nuxt kit, or a particular project's generated handlers.
export default async function h34sNuxt(options, nuxt) {
  const root = nuxt.options.rootDir
  const config = await loadConfig({ root, configFile: options?.configFile })
  const outputs = config.modules.map(module => module.output)
  nuxt.hook('nitro:config', (nitro) => {
    declareDebugFlag(nitro)
    const runtime = fileURLToPath(new URL('./runtime/', import.meta.url))
    nitro.plugins ??= []
    nitro.plugins.push(
      fileURLToPath(new URL('./runtime/h34s.mjs', import.meta.url)),
    )
    // Installed packages live in node_modules. Nitro must transform this plugin
    // so import.meta.dev reflects its build mode rather than native Node ESM, and
    // so `nitropack/runtime` resolves to Nitro's own runtime.
    nitro.externals ??= {}
    nitro.externals.inline ??= []
    nitro.externals.inline.push(runtime)
    if (!nitro.dev)
      return
    nitro.sourceMap = true
    nitro.devServer ??= {}
    nitro.devServer.watch ??= []
    nitro.devServer.watch.push(
      ...outputs.map(directory => resolve(root, directory)),
    )
    nitro.rollupConfig ??= {}
    const plugins = nitro.rollupConfig.plugins
    nitro.rollupConfig.plugins = [
      ...(Array.isArray(plugins) ? plugins : plugins ? [plugins] : []),
      scalaDevModules(root, outputs),
      scalaSourceMaps(root, outputs),
    ]
    nitro.rollupConfig.output = {
      ...nitro.rollupConfig.output,
      sourcemapExcludeSources: false,
    }
  })
}

// Declares `runtimeConfig.h34s.debug: false` (private: never sent to the browser). Declared keys
// accept `NUXT_H34S_DEBUG=true` at runtime. The Nitro plugin reads it once at startup. A value the
// application already set in `runtimeConfig.h34s.debug` wins. Nuxt's own `debug` option is not
// used: Nuxt resolves it to an object, and the `DEBUG` environment variable can set it in builds.
function declareDebugFlag(nitro) {
  nitro.runtimeConfig ??= {}
  const existing = nitro.runtimeConfig.h34s
  if (existing === undefined || existing === null)
    nitro.runtimeConfig.h34s = { debug: false }
  else if (typeof existing === 'object' && !('debug' in existing))
    nitro.runtimeConfig.h34s = { ...existing, debug: false }
}
