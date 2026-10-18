import assert from 'node:assert/strict'
// The package uses Node's runner without additional test dependencies.
// eslint-disable-next-line test/no-import-node-test
import test from 'node:test'
import { createH34sPlugin, H34S_NITRO } from '../src/runtime/context.mjs'
import { nitroHub } from './nitro-runtime-stub.mjs'

function nitroApp() {
  const hooks = {}
  return {
    hooks: {
      hook(name, fn) {
        assert.equal(hooks[name], undefined, `${name} is registered once`)
        hooks[name] = fn
      },
    },
    hooks_: hooks,
  }
}

function fakes(h34s) {
  const calls = []
  return {
    calls,
    useRuntimeConfig: (...args) => {
      calls.push(['useRuntimeConfig', ...args])
      return { databaseUrl: 'postgres://db:5432/app', public: { apiBase: '/api' }, ...(h34s === undefined ? {} : { h34s }) }
    },
    useStorage: (...args) => {
      calls.push(['useStorage', ...args])
      return { base: args[0] }
    },
  }
}

function install(options) {
  const app = nitroApp()
  createH34sPlugin(options)(app)
  return app.hooks_
}

test('the request hook installs a frozen, versioned record with lazy config and storage', (t) => {
  t.after(() => delete globalThis[H34S_NITRO])
  const nitro = fakes()
  const hooks = install({ ...nitro, development: false })
  assert.deepEqual(nitro.calls, [['useRuntimeConfig']], 'startup reads the debug flag once')
  const event = { context: { application: true } }
  hooks.request(event)
  const record = event.context.h34s
  assert.equal(event.context.application, true)
  assert.equal(record.version, 1)
  assert.equal(record.development, false)
  assert.equal(record.debug, false)
  assert.ok(Object.isFrozen(record))
  assert.deepEqual(nitro.calls, [['useRuntimeConfig']], 'request config is read only when Scala asks')
  assert.deepEqual(record.runtimeConfig(), {
    databaseUrl: 'postgres://db:5432/app',
    public: { apiBase: '/api' },
  })
  assert.deepEqual(nitro.calls, [['useRuntimeConfig'], ['useRuntimeConfig', event]])
  assert.deepEqual(record.storage(), { base: '' })
  assert.deepEqual(record.storage('cache'), { base: 'cache' })
})

test('development mode is enabled only by the boolean true', (t) => {
  t.after(() => delete globalThis[H34S_NITRO])
  for (const [development, expected] of [[true, true], [false, false], ['true', false], [1, false], [undefined, false]]) {
    const hooks = install({ ...fakes(), development })
    const event = { context: {} }
    hooks.request(event)
    assert.equal(event.context.h34s.development, expected, String(development))
    assert.equal(globalThis[H34S_NITRO].development, expected, String(development))
  }
})

test('debug mode comes only from the boolean true in runtimeConfig.h34s.debug, read at startup', (t) => {
  t.after(() => delete globalThis[H34S_NITRO])
  const cases = [
    [{ debug: true }, true],
    [{ debug: false }, false],
    [{ debug: 'true' }, false],
    [{ debug: 1 }, false],
    [{}, false],
    [undefined, false],
    [null, false],
  ]
  for (const [h34s, expected] of cases) {
    const hooks = install({ ...fakes(h34s), development: false })
    const event = { context: {} }
    hooks.request(event)
    assert.equal(event.context.h34s.debug, expected, JSON.stringify(h34s))
    assert.equal(globalThis[H34S_NITRO].debug, expected, JSON.stringify(h34s))
    assert.equal(event.context.h34s.development, false, 'debug does not imply development')
  }
})

test('a request cannot enable debug or development through headers, query or its own context', (t) => {
  t.after(() => delete globalThis[H34S_NITRO])
  const hooks = install({ ...fakes({ debug: false }), development: false })
  const forged = Object.freeze({ version: 1, development: true, debug: true })
  const event = {
    path: '/api/notes?debug=true&development=true&h34s.debug=true',
    headers: { 'x-h34s-debug': 'true', 'x-h34s-development': 'true' },
    node: { req: { headers: { 'x-h34s-debug': 'true' }, url: '/api/notes?debug=true' } },
    context: { h34s: forged, debug: true },
  }
  hooks.request(event)
  assert.notEqual(event.context.h34s, forged, 'the plugin replaces any earlier record')
  assert.equal(event.context.h34s.debug, false)
  assert.equal(event.context.h34s.development, false)
})

test('the startup record serves startup config, storage and close registration', (t) => {
  t.after(() => delete globalThis[H34S_NITRO])
  const nitro = fakes()
  install({ ...nitro, development: true })
  const startup = globalThis[H34S_NITRO]
  assert.equal(startup.version, 1)
  assert.equal(startup.development, true)
  assert.equal(startup.debug, false)
  assert.ok(Object.isFrozen(startup))
  nitro.calls.length = 0
  startup.runtimeConfig()
  assert.deepEqual(nitro.calls, [['useRuntimeConfig']], 'the startup copy is read without an event')
  assert.deepEqual(startup.storage('data'), { base: 'data' })
  const unregister = startup.onClose(() => {})
  assert.equal(typeof unregister, 'function')
  assert.equal(unregister(), true)
  assert.equal(unregister(), false)
})

test('close awaits every registered release, tolerates failures and removes the startup record', async () => {
  const hooks = install({ ...fakes(), development: false })
  const startup = globalThis[H34S_NITRO]
  const order = []
  let finishSlow
  const slow = new Promise((resolve) => {
    finishSlow = resolve
  })
  startup.onClose(async () => {
    await slow
    order.push('slow')
  })
  startup.onClose(() => {
    order.push('throws')
    throw new Error('release failed')
  })
  startup.onClose(() => {
    order.push('rejects')
    return Promise.reject(new Error('release rejected'))
  })
  const unregister = startup.onClose(() => order.push('unregistered'))
  unregister()
  let closed = false
  const closing = hooks.close().then(() => {
    closed = true
  })
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(closed, false, 'close waits for asynchronous releases')
  finishSlow()
  await closing
  assert.deepEqual(order.sort(), ['rejects', 'slow', 'throws'])
  assert.equal(globalThis[H34S_NITRO], undefined)
})

test('the default export wires Nitro\'s useRuntimeConfig and useStorage outside development', async (t) => {
  t.after(() => delete globalThis[H34S_NITRO])
  nitroHub.calls.length = 0
  const { default: plugin } = await import('../src/runtime/h34s.mjs')
  const app = nitroApp()
  plugin(app)
  const event = { context: {} }
  app.hooks_.request(event)
  assert.equal(event.context.h34s.development, false, 'import.meta.dev is not true outside Nitro')
  assert.equal(event.context.h34s.debug, false, 'the stub config declares no h34s.debug')
  assert.deepEqual(event.context.h34s.runtimeConfig(), { apiBase: '/api' })
  assert.deepEqual(event.context.h34s.storage('cache'), { mount: 'cache' })
  assert.deepEqual(nitroHub.calls, [['useRuntimeConfig'], ['useRuntimeConfig', event], ['useStorage', 'cache']])
})

test('development.mjs re-exports the h34s plugin for one release', async () => {
  const { default: current } = await import('../src/runtime/h34s.mjs')
  const { default: deprecated } = await import('../src/runtime/development.mjs')
  assert.equal(deprecated, current)
})
