import assert from 'node:assert/strict'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
// The package uses Node's runner without additional test dependencies.
// eslint-disable-next-line test/no-import-node-test
import test from 'node:test'
import { pathToFileURL } from 'node:url'
import h34sNuxt from '../src/module.mjs'
import './nitro-runtime-stub.mjs'

async function setup(t, options, nuxtOptions = {}) {
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
  const hooks = {}
  await h34sNuxt(options, {
    options: { rootDir: root, ...nuxtOptions },
    hook(name, callback) {
      assert.ok(['nitro:config', 'nitro:init'].includes(name), name)
      hooks[name] = callback
    },
  })
  return { root, configure: hooks['nitro:config'], init: hooks['nitro:init'] }
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
  const nitro = { dev: false, handlers: [existing] }
  configure(nitro)
  assert.deepEqual(nitro.handlers, [
    existing,
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
