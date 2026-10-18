import { unwatchFile, watchFile } from 'node:fs'
import { isAbsolute, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { loadConfig } from './build.mjs'
import { scalaDevModules, scalaSourceMaps } from './rollup.mjs'
import { manifestFile, readRoutes } from './routes.mjs'

// A standard Nuxt module function. No runtime dependency on an effect library,
// Nuxt kit, or a particular project's generated handlers.
export default async function h34sNuxt(options, nuxt) {
  const root = nuxt.options.rootDir
  const config = await loadConfig({ root, configFile: options?.configFile })
  const outputs = config.modules.map(module => module.output)
  const directories = outputs.map(output => resolve(root, output))
  // Nitro may copy the handler entries, so this module recognizes its own by their module path.
  const ours = handler => typeof handler?.handler === 'string' && directories.some(directory => inside(directory, handler.handler))
  nuxt.hook('nitro:config', (nitro) => {
    declareDebugFlag(nitro)
    mountRoutes(nitro, readMounts(root, config), ours)
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
  // In development, `h34s-scala dev|watch` writes the manifests again after each link. A changed
  // list of routes replaces this module's handlers and reloads Nitro, which also writes its route
  // types again. Code changes alone keep Nitro's usual worker reload. The linker deletes the
  // manifest while it links: a missing manifest keeps that output's routes until the new one.
  nuxt.hook('nitro:init', (nitro) => {
    if (!nitro.options.dev)
      return
    const manifests = outputs.map(output => resolve(root, output, manifestFile))
    let timer
    function changed() {
      clearTimeout(timer)
      timer = setTimeout(async () => {
        let routes
        try {
          routes = readRoutes({ root, config })
        }
        catch (error) {
          console.error(`[h34s] ${error.message}`)
          return
        }
        const current = (nitro.options.handlers ?? []).filter(ours)
        const kept = current.filter(handler =>
          routes.missing.some(output => inside(resolve(root, output), handler.handler)),
        )
        const next = [...routes.handlers, ...kept]
        if (key(next) === key(current))
          return
        mountRoutes(nitro.options, next, ours)
        console.info(`[h34s] Scala routes changed: ${next.length} mounted`)
        await nitro.hooks.callHook('rollup:reload')
      }, 200)
    }
    for (const manifest of manifests)
      watchFile(manifest, { interval: 500 }, changed)
    nitro.hooks.hook('close', () => {
      clearTimeout(timer)
      for (const manifest of manifests)
        unwatchFile(manifest, changed)
    })
  })
}

/** Compares handler lists by what Nitro uses, in a stable order. */
function key(handlers) {
  return JSON.stringify(
    handlers.map(({ route, method, lazy, handler }) => [route, method ?? '', lazy, handler]).sort(),
  )
}

/** The Nitro handlers of the linked routes. A missing manifest is reported, not fatal. */
function readMounts(root, config) {
  const { handlers, missing } = readRoutes({ root, config })
  for (const output of missing) {
    console.warn(
      `[h34s] ${output}/${manifestFile} is missing: run \`h34s-scala dev\` or \`h34s-scala build\` to link the Scala.js handlers and mount their routes.`,
    )
  }
  return handlers
}

/** Replaces this module's handlers in `nitro.handlers` with `next`. */
function mountRoutes(nitro, next, ours) {
  if (nitro.handlers === undefined && next.length === 0)
    return
  nitro.handlers = [...(nitro.handlers ?? []).filter(handler => !ours(handler)), ...next]
}

function inside(directory, file) {
  const path = relative(directory, file)
  return path !== '' && path !== '..' && !path.startsWith(`..${sep}`) && !isAbsolute(path)
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
