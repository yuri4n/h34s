import assert from 'node:assert/strict'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
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
  let configure
  await h34sNuxt(options, {
    options: { rootDir: root, ...nuxtOptions },
    hook(name, callback) {
      assert.equal(name, 'nitro:config')
      configure = callback
    },
  })
  return { root, configure }
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

test('declares runtimeConfig.h34s.debug from Nuxt debug mode, only for the boolean true', async (t) => {
  for (const [debug, expected] of [[true, true], [false, false], [{ hooks: true }, false], ['true', false], [undefined, false]]) {
    const { configure } = await setup(t, undefined, { debug })
    const nitro = { dev: false, runtimeConfig: { app: { baseURL: '/' }, public: {} } }
    configure(nitro)
    assert.deepEqual(nitro.runtimeConfig.h34s, { debug: expected }, JSON.stringify(debug))
    assert.deepEqual(nitro.runtimeConfig.app, { baseURL: '/' }, 'other runtime config is kept')
    assert.equal(nitro.runtimeConfig.public.h34s, undefined, 'the flag is private')
  }
})

test('keeps the application\'s own runtimeConfig.h34s values', async (t) => {
  const { configure } = await setup(t, undefined, { debug: true })
  const own = { dev: false, runtimeConfig: { h34s: { debug: false, extra: 1 } } }
  configure(own)
  assert.deepEqual(own.runtimeConfig.h34s, { debug: false, extra: 1 })
  const partial = { dev: false, runtimeConfig: { h34s: { extra: 1 } } }
  configure(partial)
  assert.deepEqual(partial.runtimeConfig.h34s, { extra: 1, debug: true })
})
