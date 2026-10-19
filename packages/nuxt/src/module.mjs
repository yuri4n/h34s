import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { loadConfig } from './build.mjs'
import { scalaDevModules, scalaPrerenderStub, scalaSourceMaps } from './rollup.mjs' // claude 2026-10-19: changed: imports scalaPrerenderStub
import { recordStubCalls, stubCallReport, takeStubCalls } from './scala-prerender-stub.mjs' // claude 2026-10-19: created: the stub's request report (review F1)

// A standard Nuxt module function. No runtime dependency on an effect library,
// Nuxt kit, or a particular project's generated handlers.
export default async function h34sNuxt(options, nuxt) {
  const scalaPrerender = scalaPrerenderOption(options?.scalaPrerender) // claude 2026-10-19: created: validated before any other work
  const root = nuxt.options.rootDir
  const config = await loadConfig({ root, configFile: options?.configFile })
  const outputs = config.modules.map(module => module.output)
  // claude 2026-10-19: changed: 'stub' replaces the linked Scala.js in Nitro's prerender bundle only, in production builds, and reports the requests that reached the stub (review F1)
  if (scalaPrerender === 'stub' && !nuxt.options.dev) {
    nuxt.hook('nitro:init', (nitro) => {
      nitro.hooks.hook('prerender:init', (prerenderer) => {
        recordStubCalls()
        prerenderer.hooks.hook('rollup:before', (_nitro, rollupConfig) => {
          const plugins = rollupConfig.plugins
          rollupConfig.plugins = Array.isArray(plugins) ? plugins : plugins ? [plugins] : []
          rollupConfig.plugins.unshift(scalaPrerenderStub(root, outputs))
        })
      })
      nitro.hooks.hook('prerender:done', result => reportStubCalls(nitro, result)) // claude 2026-10-19: changed: passes Nitro's result (review F1)
    })
  }
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

// claude 2026-10-19: created: 'bundle' (the default) or 'stub'; anything else is a TypeError
function scalaPrerenderOption(value = 'bundle') {
  if (value === 'bundle' || value === 'stub')
    return value
  const shown = typeof value === 'string' ? `'${value}'` : typeof value
  throw new TypeError(
    `@yuri4n/h34s-nuxt: scalaPrerender must be 'bundle' or 'stub', got ${shown}`,
  )
}

// claude 2026-10-19: created: fails the build, or warns, when a prerendered page reached the stub (review F1)
// A page that catches the stub's 501, as useFetch does, still prerenders with status 200, so
// Nitro's own check of failed routes does not see it. Nuxt turns on `prerender.failOnError`; with
// it, the build stops here, before Nitro builds the server output.
// claude 2026-10-19: changed: lets Nitro stop the build when it fails on its own (review F1)
// Nitro 2.13.1 calls `prerender:done` before its own check. When that check is about to stop the
// build (`failOnError` and at least one failed route), a throw here would hide Nitro's summary of
// the failed routes, so h34s logs its report as an error and lets Nitro throw.
function reportStubCalls(nitro, result) {
  const calls = takeStubCalls()
  if (calls.length === 0)
    return
  const report = stubCallReport(calls)
  if (!nitro.options?.prerender?.failOnError) {
    nitro.logger.warn(report)
    return
  }
  const failed = result?.failedRoutes
  if ((failed?.length ?? failed?.size ?? 0) > 0) {
    nitro.logger.error(report)
    return
  }
  throw new Error(report)
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
