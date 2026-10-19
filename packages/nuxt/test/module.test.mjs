import assert from 'node:assert/strict'
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises' // claude 2026-10-19: changed: mkdir for the scalaPrerender tests
import { tmpdir } from 'node:os'
import { join } from 'node:path'
// The package uses Node's runner without additional test dependencies.
// eslint-disable-next-line test/no-import-node-test
import test from 'node:test'
import { pathToFileURL } from 'node:url'
import h34sNuxt from '../src/module.mjs'
import './nitro-runtime-stub.mjs'

// claude 2026-10-19: changed: records every Nuxt hook; only the names in `allowed` may be registered, once each
async function setup(t, options, nuxtOptions = {}, allowed = ['nitro:config']) {
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
      assert.ok(!hooks.has(name), `Nuxt hook ${name} registered twice`)
      hooks.set(name, callback)
    },
  })
  return { root, configure: hooks.get('nitro:config'), hooks }
}

// claude 2026-10-19: created: a Nitro instance whose hooks only record their callbacks
function recordingNitro() {
  const hooks = new Map()
  return {
    hooks,
    nitro: {
      hooks: {
        hook(name, callback) {
          assert.ok(!hooks.has(name), `Nitro hook ${name} registered twice`)
          hooks.set(name, callback)
        },
      },
    },
  }
}

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
test('registers the prerender hook only for scalaPrerender: \'stub\' in production builds', async (t) => {
  const cases = [
    [undefined, {}, ['nitro:config']],
    [{}, {}, ['nitro:config']],
    [{ scalaPrerender: undefined }, {}, ['nitro:config']],
    [{ scalaPrerender: 'bundle' }, {}, ['nitro:config']],
    [{ scalaPrerender: 'bundle' }, { dev: true }, ['nitro:config']],
    [{ scalaPrerender: 'stub' }, { dev: true }, ['nitro:config']],
    [{ scalaPrerender: 'stub' }, {}, ['nitro:config', 'nitro:init']],
    [{ scalaPrerender: 'stub' }, { dev: false }, ['nitro:config', 'nitro:init']],
  ]
  for (const [options, nuxtOptions, expected] of cases) {
    const { hooks } = await setup(t, options, nuxtOptions, ['nitro:config', 'nitro:init'])
    assert.deepEqual([...hooks.keys()].sort(), expected, JSON.stringify({ options, nuxtOptions }))
  }
})

// claude 2026-10-19: created: 'stub' puts the stub plugin first in the prerender Rollup build only
test('scalaPrerender: \'stub\' puts the stub plugin first in the prerender Rollup build and leaves the server build alone', async (t) => {
  const { root, configure, hooks } = await setup(
    t,
    { scalaPrerender: 'stub' },
    {},
    ['nitro:config', 'nitro:init'],
  )
  const server = { dev: false }
  configure(server)
  assert.equal(server.rollupConfig, undefined, 'the node-server build gets no Rollup plugin')

  const main = recordingNitro()
  hooks.get('nitro:init')(main.nitro)
  assert.deepEqual([...main.hooks.keys()], ['prerender:init'])
  const renderer = recordingNitro()
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
