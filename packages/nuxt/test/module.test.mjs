import assert from 'node:assert/strict'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join, relative, sep } from 'node:path' // claude 2026-10-20: changed: relative and sep for the models alias test
import { setTimeout as delay } from 'node:timers/promises'
// The package uses Node's runner without additional test dependencies.
// eslint-disable-next-line test/no-import-node-test
import test from 'node:test'
import { pathToFileURL } from 'node:url'
import h34sNuxt from '../src/module.mjs'
import { takeStubCalls } from '../src/scala-prerender-stub.mjs' // claude 2026-10-19: created: closes the stub's request record (review F1)
import './nitro-runtime-stub.mjs'

// claude 2026-10-19: changed: records every Nuxt hook in order; only the names in `allowed` may be registered, and nitro:config once
async function setup(t, options, nuxtOptions = {}, allowed = ['nitro:config', 'nitro:init']) {
  const root = await mkdtemp(join(tmpdir(), 'h34s-nuxt-'))
  t.after(() => rm(root, { recursive: true, force: true }))
  const file = options?.configFile ?? 'h34s.config.mjs'
  await writeFile(
    join(root, file),
    `export default { modules: [
    { output: 'server/.scala', entries: ['main.js'] },
    { project: 'kyo', output: 'server/.scala-kyo', entries: ['main.js'] }
  ] }`,
  )
  const hooks = new Map()
  await h34sNuxt(options, {
    options: { rootDir: root, ...nuxtOptions },
    hook(name, callback) {
      assert.ok(allowed.includes(name), `unexpected Nuxt hook ${name}`)
      hooks.set(name, [...(hooks.get(name) ?? []), callback])
    },
  })
  assert.equal(hooks.get('nitro:config')?.length, 1, 'nitro:config registered once')
  // claude 2026-10-19: created: every nitro:init hook: the development manifest watcher, and the prerender hooks of scalaPrerender: 'stub' in production builds
  const init = nitro => (hooks.get('nitro:init') ?? []).forEach(hook => hook(nitro))
  return { root, configure: hooks.get('nitro:config')[0], init, hooks }
}

// claude 2026-10-19: created: a Nitro instance whose hooks only record their callbacks, with prerender options and a recording logger (review F1)
function recordingNitro(prerender = { failOnError: true }, dev = false) {
  const hooks = new Map()
  const warnings = []
  const errors = [] // claude 2026-10-19: created: the logger's error lines
  return {
    hooks,
    warnings,
    errors, // claude 2026-10-19: created: the logger's error lines
    nitro: {
      options: { dev, prerender },
      logger: { warn: message => warnings.push(message), error: message => errors.push(message) }, // claude 2026-10-19: created: also records errors
      hooks: {
        hook(name, callback) {
          assert.ok(!hooks.has(name), `Nitro hook ${name} registered twice`)
          hooks.set(name, callback)
        },
      },
    },
  }
}

// claude 2026-10-19: created: runs the module's prerender hooks on recording Nitro instances
async function prerenderHooks(t, prerender) {
  const { root, configure, init } = await setup(t, { scalaPrerender: 'stub' })
  const main = recordingNitro(prerender)
  init(main.nitro)
  const renderer = recordingNitro()
  return { root, configure, main, renderer }
}

async function manifest(root, output, routes) {
  await mkdir(join(root, output), { recursive: true })
  await writeFile(join(root, output, 'h34s-routes.json'), JSON.stringify({ version: 1, routes, declarations: [] }))
}

test('mounts the routes of each output manifest as lazy Nitro handlers, after existing ones', async (t) => {
  const { root, configure } = await setup(t)
  await manifest(root, 'server/.scala', [
    { module: 'notes-get.js', route: '/api/notes', method: 'get' },
    { module: 'test.js', route: '/api/test' },
  ])
  await manifest(root, 'server/.scala-kyo', [{ module: 'greeting.js', route: '/api/kyo/greeting', method: 'post' }])
  const existing = { route: '/api/own', handler: join(root, 'server/own.ts') }
  // A handler you register yourself stays, also when its module is in a Scala.js output.
  const manual = { route: '/api/manual', handler: join(root, 'server/.scala/manual.js') }
  const nitro = { dev: false, handlers: [existing, manual] }
  configure(nitro)
  assert.deepEqual(nitro.handlers, [
    existing,
    manual,
    { route: '/api/notes', method: 'get', lazy: true, handler: join(root, 'server/.scala/notes-get.js') },
    { route: '/api/test', lazy: true, handler: join(root, 'server/.scala/test.js') },
    { route: '/api/kyo/greeting', method: 'post', lazy: true, handler: join(root, 'server/.scala-kyo/greeting.js') },
  ])
})

test('rejects a manifest whose module leaves the output directory', async (t) => {
  const { root, configure } = await setup(t)
  for (const module of ['../escape.js', '/tmp/x.js', 'nested/x.js', 'x.ts']) {
    await manifest(root, 'server/.scala', [{ module, route: '/api/x' }])
    assert.throws(() => configure({ dev: false }), /invalid module/, module)
  }
  await manifest(root, 'server/.scala', [{ module: 'x.js', route: 'api/x' }])
  assert.throws(() => configure({ dev: false }), /must start with/)
})

test('development replaces its routes when a manifest changes, and keeps them while one is missing', async (t) => {
  const { root, configure, init } = await setup(t)
  await manifest(root, 'server/.scala', [{ module: 'a.js', route: '/api/a', method: 'get' }])
  await manifest(root, 'server/.scala-kyo', [{ module: 'k.js', route: '/api/k' }])
  const config = { dev: true }
  configure(config)
  const reloads = []
  const closers = []
  const nitro = {
    options: { dev: true, handlers: config.handlers.map(handler => ({ ...handler })) },
    hooks: {
      hook(name, fn) {
        assert.equal(name, 'close')
        closers.push(fn)
      },
      async callHook(name) {
        reloads.push(name)
      },
    },
  }
  init(nitro)
  t.after(() => closers.forEach(close => close()))
  const routes = () => nitro.options.handlers.map(handler => `${handler.method ?? '*'} ${handler.route}`).sort()
  // The linker deletes the manifest while it links: no reload, the routes stay.
  await rm(join(root, 'server/.scala/h34s-routes.json'))
  await delay(1_500)
  assert.deepEqual(reloads, [])
  assert.deepEqual(routes(), ['* /api/k', 'get /api/a'])
  // The same routes again: no reload.
  await manifest(root, 'server/.scala', [{ module: 'a.js', route: '/api/a', method: 'get' }])
  await delay(1_500)
  assert.deepEqual(reloads, [])
  // A new route: one reload with the new list.
  await manifest(root, 'server/.scala', [
    { module: 'a.js', route: '/api/a', method: 'get' },
    { module: 'b.js', route: '/api/b', method: 'delete' },
  ])
  await delay(1_500)
  assert.deepEqual(reloads, ['rollup:reload'])
  assert.deepEqual(routes(), ['* /api/k', 'delete /api/b', 'get /api/a'])
})

test('registers maps, direct loading, output watches and diagnostics in development', async (t) => {
  const { root, configure } = await setup(t)
  const existing = { name: 'existing' }
  const nitro = {
    dev: true,
    plugins: ['existing-plugin'],
    devServer: { watch: ['/other'] },
    rollupConfig: { plugins: existing, output: { format: 'esm' } },
  }
  configure(nitro)
  assert.equal(nitro.sourceMap, true)
  assert.deepEqual(nitro.devServer.watch, [
    '/other',
    join(root, 'server/.scala'),
    join(root, 'server/.scala-kyo'),
  ])
  assert.deepEqual(
    nitro.rollupConfig.plugins.map(plugin => plugin.name),
    ['existing', 'h34s-scala-dev-modules', 'h34s-scala-source-maps'],
  )
  assert.deepEqual(nitro.rollupConfig.output, {
    format: 'esm',
    sourcemapExcludeSources: false,
  })
  assert.equal(nitro.plugins[0], 'existing-plugin')
  assert.ok(nitro.plugins[1].endsWith('/runtime/h34s.mjs'))
  assert.ok(nitro.externals.inline[0].endsWith('/runtime/'))
})

test('production leaves source maps, bundling and watches unchanged and denies diagnostics by default', async (t) => {
  const { configure } = await setup(t, { configFile: 'custom.mjs' })
  const nitro = { dev: false, sourceMap: false }
  configure(nitro)
  assert.deepEqual(Object.keys(nitro).sort(), [
    'dev',
    'externals',
    'plugins',
    'runtimeConfig',
    'sourceMap',
  ])
  assert.deepEqual(nitro.runtimeConfig, { h34s: { debug: false } })
  assert.equal(nitro.sourceMap, false)
  const { default: plugin } = await import(pathToFileURL(nitro.plugins[0]))
  let request
  plugin({
    hooks: {
      hook(name, fn) {
        if (name === 'request')
          request = fn
        else
          assert.equal(name, 'close')
      },
    },
  })
  const event = { context: { application: true } }
  request(event)
  assert.equal(event.context.application, true)
  assert.equal(event.context.h34s.version, 1)
  assert.equal(event.context.h34s.development, false)
  delete globalThis[Symbol.for('h34s.nitro')]
})

test('declares runtimeConfig.h34s.debug as false, whatever Nuxt\'s own debug option says', async (t) => {
  // Nuxt resolves `debug: true` to an object such as this one before modules run, and `DEBUG` in
  // the build environment can set it: neither may enable diagnostics.
  const resolved = { templates: true, modules: true, watchers: true, hooks: { server: true, client: true }, nitro: true, router: true, hydration: true }
  for (const debug of [true, false, resolved, { hooks: true }, 'true', undefined]) {
    const { configure } = await setup(t, undefined, { debug })
    const nitro = { dev: false, runtimeConfig: { app: { baseURL: '/' }, public: {} } }
    configure(nitro)
    assert.deepEqual(nitro.runtimeConfig.h34s, { debug: false }, JSON.stringify(debug))
    assert.deepEqual(nitro.runtimeConfig.app, { baseURL: '/' }, 'other runtime config is kept')
    assert.equal(nitro.runtimeConfig.public.h34s, undefined, 'the flag is private')
  }
})

test('keeps the application\'s own runtimeConfig.h34s values', async (t) => {
  const { configure } = await setup(t, undefined, { debug: true })
  const own = { dev: false, runtimeConfig: { h34s: { debug: true, extra: 1 } } }
  configure(own)
  assert.deepEqual(own.runtimeConfig.h34s, { debug: true, extra: 1 }, 'runtimeConfig.h34s.debug: true in nuxt.config wins')
  const partial = { dev: false, runtimeConfig: { h34s: { extra: 1 } } }
  configure(partial)
  assert.deepEqual(partial.runtimeConfig.h34s, { extra: 1, debug: false })
})

// claude 2026-10-19: created: the prerender hook exists only for scalaPrerender: 'stub' in production builds
// The development manifest watcher always registers a nitro:init hook; in development it adds a
// Nitro 'close' hook, which this test calls to stop the watcher.
test('registers the prerender hook only for scalaPrerender: \'stub\' in production builds', async (t) => {
  const cases = [
    [undefined, {}, 1, []],
    [{}, {}, 1, []],
    [{ scalaPrerender: undefined }, {}, 1, []],
    [{ scalaPrerender: 'bundle' }, {}, 1, []],
    [{ scalaPrerender: 'bundle' }, { dev: true }, 1, ['close']],
    [{ scalaPrerender: 'stub' }, { dev: true }, 1, ['close']],
    [{ scalaPrerender: 'stub' }, {}, 2, ['prerender:init', 'prerender:done']],
    [{ scalaPrerender: 'stub' }, { dev: false }, 2, ['prerender:init', 'prerender:done']],
  ]
  for (const [options, nuxtOptions, inits, expected] of cases) {
    const context = JSON.stringify({ options, nuxtOptions })
    const { hooks, init } = await setup(t, options, nuxtOptions)
    assert.equal(hooks.get('nitro:init').length, inits, context)
    const nitro = recordingNitro(undefined, nuxtOptions.dev === true)
    init(nitro.nitro)
    nitro.hooks.get('close')?.()
    assert.deepEqual([...nitro.hooks.keys()], expected, context)
  }
})

// claude 2026-10-19: created: 'stub' puts the stub plugin first in the prerender Rollup build only; the main Nitro also gets prerender:done (review F1)
test('scalaPrerender: \'stub\' puts the stub plugin first in the prerender Rollup build and leaves the server build alone', async (t) => {
  const { root, configure, main, renderer } = await prerenderHooks(t)
  t.after(takeStubCalls)
  const server = { dev: false }
  configure(server)
  assert.equal(server.rollupConfig, undefined, 'the node-server build gets no Rollup plugin')

  assert.deepEqual([...main.hooks.keys()], ['prerender:init', 'prerender:done'])
  main.hooks.get('prerender:init')(renderer.nitro)
  assert.deepEqual([...renderer.hooks.keys()], ['rollup:before'])
  const rollupBefore = renderer.hooks.get('rollup:before')

  const existing = { name: 'nitro-resolver' }
  const rollupConfig = { plugins: [existing] }
  rollupBefore(renderer.nitro, rollupConfig)
  assert.deepEqual(
    rollupConfig.plugins.map(plugin => plugin.name),
    ['h34s-scala-prerender-stub', 'nitro-resolver'],
  )
  assert.equal(rollupConfig.plugins[1], existing)
  const stub = rollupConfig.plugins[0]
  for (const directory of ['server/.scala', 'server/.scala-kyo', 'server/.scala-other']) {
    await mkdir(join(root, directory), { recursive: true })
    await writeFile(join(root, directory, 'main.js'), 'export default 1\n')
  }
  const resolve = source => stub.resolveId.handler(source, join(root, 'server/api/route.ts'))
  assert.equal(typeof (await resolve('../.scala/main.js')), 'string', 'the configured ZIO output')
  assert.equal(typeof (await resolve('../.scala-kyo/main')), 'string', 'the configured Kyo output')
  // claude 2026-10-19: created: the module mounts each route as a Nitro handler with the absolute path of its linked file
  assert.equal(typeof (await stub.resolveId.handler(join(root, 'server/.scala/main.js'))), 'string', 'an absolute path, as a mounted route imports it')
  assert.equal(await resolve('../.scala-other/main.js'), null, 'an unconfigured directory')

  for (const plugins of [undefined, existing]) {
    const config = { plugins }
    rollupBefore(renderer.nitro, config)
    assert.deepEqual(
      config.plugins.map(plugin => plugin.name),
      ['h34s-scala-prerender-stub', ...(plugins ? ['nitro-resolver'] : [])],
    )
  }
})

// claude 2026-10-19: created: prerender:done stops the build, or warns, when a request reached the stub (review F1)
test('scalaPrerender: \'stub\' reports requests that reached the stub after prerendering', async (t) => {
  const warnings = []
  const warn = console.warn
  console.warn = (...args) => warnings.push(args.join(' '))
  t.after(() => {
    console.warn = warn
    takeStubCalls()
  })
  const event = { method: 'GET', path: '/api/notes', node: { req: { headers: { 'x-nitro-prerender': '/notes' } } } }

  async function prerender(options) {
    const { root, main, renderer } = await prerenderHooks(t, options)
    main.hooks.get('prerender:init')(renderer.nitro)
    const rollupConfig = { plugins: [] }
    renderer.hooks.get('rollup:before')(renderer.nitro, rollupConfig)
    const [stub] = rollupConfig.plugins
    await mkdir(join(root, 'server/.scala'), { recursive: true })
    await writeFile(join(root, 'server/.scala/main.js'), 'export default 1\n')
    const loaded = stub.load(await stub.resolveId.handler('../.scala/main.js', join(root, 'server/api/route.ts')))
    await writeFile(join(root, 'stub.mjs'), loaded.code)
    const { default: handler } = await import(pathToFileURL(join(root, 'stub.mjs')).href)
    return { main, handler }
  }

  const quiet = await prerender({ failOnError: true })
  quiet.main.hooks.get('prerender:done')({ prerenderedRoutes: [], failedRoutes: [] })
  assert.deepEqual(quiet.main.warnings, [], 'no request, no report')

  const failing = await prerender({ failOnError: true })
  assert.throws(() => failing.handler(event), { statusCode: 501 })
  assert.throws(
    () => failing.main.hooks.get('prerender:done')({ prerenderedRoutes: [], failedRoutes: [] }),
    { message: /scalaPrerender is 'stub'[\s\S]*\n {2}GET \/api\/notes, while Nuxt prerendered \/notes\n/ },
  )
  assert.deepEqual(failing.main.warnings, [])
  assert.deepEqual(failing.main.errors, []) // claude 2026-10-19: created: no failed route, so h34s throws instead of logging

  const lenient = await prerender({ failOnError: false })
  assert.throws(() => lenient.handler(event), { statusCode: 501 })
  lenient.main.hooks.get('prerender:done')({ prerenderedRoutes: [], failedRoutes: [] })
  assert.equal(lenient.main.warnings.length, 1)
  assert.match(lenient.main.warnings[0], /\n {2}GET \/api\/notes, while Nuxt prerendered \/notes\n/)
  lenient.main.hooks.get('prerender:done')({ prerenderedRoutes: [], failedRoutes: [] })
  assert.equal(lenient.main.warnings.length, 1, 'each prerender run reports its own requests once')

  // claude 2026-10-19: created: Nitro 2.13.1 calls prerender:done before its own check of failed routes; when that check will stop the build, h34s logs the report and lets Nitro throw with its summary (review F1)
  // Nitro passes `failedRoutes` as an array; a Set is accepted too.
  for (const failedRoutes of [[{ route: '/api/notes' }], new Set([{ route: '/api/notes' }])]) {
    const nitroFails = await prerender({ failOnError: true })
    assert.throws(() => nitroFails.handler(event), { statusCode: 501 })
    nitroFails.main.hooks.get('prerender:done')({ prerenderedRoutes: [], failedRoutes })
    assert.equal(nitroFails.main.errors.length, 1, 'the report is logged as an error')
    assert.match(nitroFails.main.errors[0], /scalaPrerender is 'stub'[\s\S]*\n {2}GET \/api\/notes, while Nuxt prerendered \/notes\n/)
    assert.deepEqual(nitroFails.main.warnings, [])
  }
  // claude 2026-10-19: created: without failOnError, failed routes do not change the warning
  const lenientFailed = await prerender({ failOnError: false })
  assert.throws(() => lenientFailed.handler(event), { statusCode: 501 })
  lenientFailed.main.hooks.get('prerender:done')({ prerenderedRoutes: [], failedRoutes: [{ route: '/api/notes' }] })
  assert.equal(lenientFailed.main.warnings.length, 1)
  assert.deepEqual(lenientFailed.main.errors, [])
  assert.equal(warnings.length, 5, 'the stub logged each request') // claude 2026-10-19: created: three more requests above
})

// claude 2026-10-20: created: the #h34s/models alias for several outputs, and an application alias that wins (ADR-0014)
test('maps #h34s/models to a build declaration that re-exports the models of every output', async (t) => {
  const buildDir = join(tmpdir(), 'h34s-build-dir')
  const nuxtOptions = { buildDir, alias: { '#app': '/app' }, build: { templates: [] } }
  const { root } = await setup(t, undefined, nuxtOptions)
  assert.equal(nuxtOptions.alias['#h34s/models'], join(buildDir, 'h34s/models'))
  assert.equal(nuxtOptions.alias['#app'], '/app')
  const [template] = nuxtOptions.build.templates
  assert.equal(template.filename, 'h34s/models.d.ts')
  assert.equal(template.write, true)
  const lines = template.getContents().split('\n').filter(line => line.startsWith('export'))
  assert.deepEqual(lines, [
    `export type * from '${relative(join(buildDir, 'h34s'), join(root, 'server/.scala/h34s-models')).split(sep).join('/')}'`,
    `export type * from '${relative(join(buildDir, 'h34s'), join(root, 'server/.scala-kyo/h34s-models')).split(sep).join('/')}'`,
  ])
  const own = { alias: { '#h34s/models': '/mine' }, build: { templates: [] } }
  await setup(t, undefined, own)
  assert.equal(own.alias['#h34s/models'], '/mine')
  assert.deepEqual(own.build.templates, [])
})

// claude 2026-10-20: created: the #h34s/models alias for one output (ADR-0014)
test('maps #h34s/models to the models file of the only output', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'h34s-nuxt-'))
  t.after(() => rm(root, { recursive: true, force: true }))
  await writeFile(join(root, 'h34s.config.mjs'), `export default { modules: [{ output: 'server/.scala', entries: ['main.js'] }] }`)
  const options = { rootDir: root, build: { templates: [] } }
  await h34sNuxt(undefined, { options, hook() {} })
  assert.equal(options.alias['#h34s/models'], join(root, 'server/.scala/h34s-models'))
  assert.deepEqual(options.build.templates, [])
})

// claude 2026-10-19: created: an unknown scalaPrerender value fails before any hook is registered
test('rejects an unknown scalaPrerender value with a TypeError before registering hooks', async (t) => {
  const root = await mkdtemp(join(tmpdir(), 'h34s-nuxt-'))
  t.after(() => rm(root, { recursive: true, force: true }))
  const nuxt = {
    options: { rootDir: root },
    hook: name => assert.fail(`registered ${name}`),
  }
  for (const scalaPrerender of ['Stub', 'none', '', true, false, null, 0, {}, ['stub'], Object.create(null), Symbol('stub')]) {
    await assert.rejects(
      h34sNuxt({ scalaPrerender }, nuxt),
      { name: 'TypeError', message: /scalaPrerender must be 'bundle' or 'stub'/ },
      String(typeof scalaPrerender),
    )
  }
})
