import { unwatchFile, watchFile } from 'node:fs'
import { isAbsolute, join, relative, resolve, sep } from 'node:path' // claude 2026-10-20: changed: join for the models alias
import { fileURLToPath } from 'node:url'
import { loadConfig } from './build.mjs'
import { scalaDevModules, scalaPrerenderStub, scalaSourceMaps } from './rollup.mjs' // claude 2026-10-19: changed: imports scalaPrerenderStub
import { manifestFile, modelsFile, readRoutes } from './routes.mjs' // claude 2026-10-20: changed: imports modelsFile
import { recordStubCalls, stubCallReport, takeStubCalls } from './scala-prerender-stub.mjs' // claude 2026-10-19: created: the stub's request report (review F1)
import { packageVersion } from './version.mjs' // claude 2026-10-20: created: the version that the init hint names (ADR-0009)

// A standard Nuxt module function. No runtime dependency on an effect library,
// Nuxt kit, or a particular project's generated handlers.
export default async function h34sNuxt(options, nuxt) {
  const scalaPrerender = scalaPrerenderOption(options?.scalaPrerender) // claude 2026-10-19: created: validated before any other work
  const root = nuxt.options.rootDir
  // claude 2026-10-20: changed: a missing h34s.config.mjs names the init command (ADR-0009, decision 11)
  const config = await loadConfig({ root, configFile: options?.configFile }).catch((error) => {
    throw initHint(error, root, options?.configFile)
  })
  const outputs = config.modules.map(module => module.output)
  registerModels(nuxt, root, outputs) // claude 2026-10-20: created: the #h34s/models alias (ADR-0014)
  // claude 2026-10-19: created: 'stub' replaces the linked Scala.js in Nitro's prerender bundle only, in production builds, and reports the requests that reached the stub (review F1)
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
      nitro.hooks.hook('prerender:done', result => reportStubCalls(nitro, result)) // claude 2026-10-19: created: passes Nitro's result (review F1)
    })
  }
  // The handlers this module mounted. Nitro may copy the entries, so they are compared by value.
  let mounted = new Set()
  const ours = handler => mounted.has(identity(handler))
  function mount(nitro, next) {
    const kept = (nitro.handlers ?? []).filter(handler => !ours(handler))
    if (nitro.handlers !== undefined || next.length > 0)
      nitro.handlers = [...kept, ...next]
    mounted = new Set(next.map(identity))
  }
  nuxt.hook('nitro:config', (nitro) => {
    declareDebugFlag(nitro)
    mount(nitro, readMounts(root, config))
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
      scalaSourceMaps(root, outputs, [config.sbtRoot]), // claude 2026-10-20: changed: also embeds the Scala sources of the sbt build (ADR-0009)
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
        mount(nitro.options, next)
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

// claude 2026-10-20: created: maps #h34s/models to the models of the configured outputs (ADR-0014)
/**
 * Registers the alias `#h34s/models`, so that the app writes `import type { Note } from
 * '#h34s/models'`. Nuxt turns the alias into a `paths` entry of its generated tsconfig. With one
 * output, the alias is that output's `h34s-models.d.ts`. With several, it is a declaration in the
 * build directory that re-exports the models of every output; `h34s-scala` makes sure that a name
 * means one structure across the outputs. An alias that the application already set wins.
 */
function registerModels(nuxt, root, outputs) {
  const alias = '#h34s/models'
  const options = nuxt.options
  options.alias ??= {}
  if (options.alias[alias] !== undefined || outputs.length === 0)
    return
  const models = output => resolve(root, output, modelsFile.replace(/\.d\.ts$/, ''))
  if (outputs.length === 1) {
    options.alias[alias] = models(outputs[0])
    return
  }
  const directory = join(options.buildDir ?? resolve(root, '.nuxt'), 'h34s')
  const lines = outputs.map((output) => {
    const path = relative(directory, models(output)).split(sep).join('/')
    return `export type * from '${path.startsWith('.') ? path : `./${path}`}'\n`
  })
  options.build ??= {}
  options.build.templates ??= []
  options.build.templates.push({
    filename: 'h34s/models.d.ts',
    write: true,
    getContents: () => `// Generated by the h34s Nuxt module: the models of every Scala.js output. Do not edit.\n${lines.join('')}`,
  })
  options.alias[alias] = join(directory, 'models')
}

/** A handler entry by what Nitro uses. */
function identity({ route, method, lazy, handler }) {
  return JSON.stringify([route, method ?? '', lazy === true, handler])
}

/** Compares handler lists in a stable order. */
function key(handlers) {
  return JSON.stringify(handlers.map(identity).sort())
}

// claude 2026-10-20: created: the error hint that names init with this package's version (ADR-0009, decision 11)
/**
 * Adds the init command to the error of a missing `h34s.config.mjs`. A custom `configFile` keeps
 * the plain error: init writes `h34s.config.mjs`, not that file.
 */
function initHint(error, root, configFile = 'h34s.config.mjs') {
  if (error?.code !== 'H34S_CONFIG_MISSING' || resolve(root, configFile) !== resolve(root, 'h34s.config.mjs'))
    return error
  return Object.assign(
    new Error(`${error.message}. Run pnpm dlx @yuri4n/h34s-nuxt@${packageVersion()} init in ${root} to set up h34s.`, { cause: error }),
    { code: error.code },
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

function inside(directory, file) {
  const path = relative(directory, file)
  return path !== '' && path !== '..' && !path.startsWith(`..${sep}`) && !isAbsolute(path)
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
// claude 2026-10-19: created: lets Nitro stop the build when it fails on its own (review F1)
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
