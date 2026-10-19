// claude 2026-10-19: created: tests for scalaPrerenderStub, the plugin behind scalaPrerender: 'stub' (issue #30)
import assert from 'node:assert/strict'
import { mkdir, mkdtemp, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
// The package uses Node's runner without additional test dependencies.
// eslint-disable-next-line test/no-import-node-test
import test from 'node:test'
import { pathToFileURL } from 'node:url'
import { scalaPrerenderStub } from '../src/rollup.mjs'
import { recordStubCalls, stubCallReport, takeStubCalls } from '../src/scala-prerender-stub.mjs' // claude 2026-10-19: created: the request record (review F1)

async function fixture(t, outputDirectories) {
  const root = await mkdtemp(join(tmpdir(), 'h34s-prerender-stub-'))
  t.after(() => rm(root, { recursive: true, force: true }))
  async function put(path, content = 'export default function scalaHandler() {}\n') {
    const file = join(root, path)
    await mkdir(dirname(file), { recursive: true })
    await writeFile(file, content)
    return file
  }
  const importer = await put('server/api/handler.ts', '')
  const plugin = scalaPrerenderStub(root, outputDirectories)
  const resolveId = (source, from = importer) =>
    plugin.resolveId.handler(source, from)
  return { root, put, importer, plugin, resolveId }
}

test('resolves relative, absolute, file URL and extensionless imports of both default outputs to one stub', async (t) => {
  const f = await fixture(t)
  assert.equal(f.plugin.name, 'h34s-scala-prerender-stub')
  assert.equal(f.plugin.resolveId.order, 'pre')
  const ids = new Set()
  for (const directory of ['.scala', '.scala-kyo']) {
    const file = await f.put(`server/${directory}/hello world.js`)
    for (const [source, importer] of [
      [`../${directory}/hello world.js`, f.importer],
      [`../${directory}/hello world`, f.importer],
      [`../${directory}/hello world`, pathToFileURL(f.importer).href],
      [file, f.importer],
      [file, undefined],
      [pathToFileURL(file).href, undefined],
    ]) {
      const id = await f.plugin.resolveId.handler(source, importer)
      assert.equal(typeof id, 'string', source)
      assert.ok(id.startsWith('\0'), 'a virtual id that other plugins skip')
      ids.add(id)
    }
  }
  assert.equal(ids.size, 1, 'every output file resolves to the same stub module')
})

test('leaves unrelated, virtual, missing, non-JavaScript and escaping imports to Nitro', async (t) => {
  const f = await fixture(t)
  const linked = await f.put('server/.scala/main.js')
  await f.put('server/.scala/main.ts')
  await f.put('server/.scala-other/main.js')
  await f.put('server/ordinary.js')
  await mkdir(join(f.root, 'server/.scala/directory.js'))
  const outside = await fixture(t)
  await symlink(await outside.put('private.js'), join(f.root, 'server/.scala/escape.js'))
  for (const source of [
    'h3',
    'node:fs',
    '\0virtual',
    'https://example.test/main.js',
    '../ordinary.js',
    '../.scala-other/main.js',
    '../.scala/../ordinary.js',
    '../.scala/missing.js',
    '../.scala/directory.js',
    '../.scala/main.ts',
    '../.scala/main.js.map',
    '../.scala/main.js?raw',
    `${pathToFileURL(linked).href}?raw`,
    '../.scala/escape.js',
  ]) {
    assert.equal(await f.resolveId(source), null, source)
  }
  assert.equal(await f.resolveId('../.scala/main.js', '\0virtual'), null)
  assert.equal(await f.plugin.load(linked), null, 'real files are never loaded by the stub plugin')
})

test('uses the configured output directories and rejects directories outside the project root', async (t) => {
  const f = await fixture(t, ['linked/server'])
  const configured = await f.put('linked/server/entry.js')
  const unconfigured = await f.put('server/.scala/entry.js')
  assert.equal(typeof (await f.resolveId(configured)), 'string')
  assert.equal(await f.resolveId(unconfigured), null)
  for (const directory of ['.', '', '..', '../outside', f.root]) {
    assert.throws(
      () => scalaPrerenderStub(f.root, [directory]),
      { name: 'TypeError', message: /inside the project root/ },
    )
  }
})

// claude 2026-10-19: created: replaces console.warn for one test and returns the lines it got
function captureWarnings(t) {
  const warnings = []
  const warn = console.warn
  console.warn = (...args) => warnings.push(args.join(' '))
  t.after(() => {
    console.warn = warn
  })
  return warnings
}

// claude 2026-10-19: created: imports the stub module that the plugin loads
async function loadStub(f) {
  const id = await f.resolveId(await f.put('server/.scala/main.js'))
  const loaded = f.plugin.load(id)
  return import(pathToFileURL(await f.put('stub.mjs', loaded.code)).href)
}

// claude 2026-10-19: created: the stub also logs the request it answers (review F1)
test('loads a stub whose exports are h3 event handlers that answer 501 and name the option', async (t) => {
  const f = await fixture(t)
  const warnings = captureWarnings(t)
  const id = await f.resolveId(await f.put('server/.scala/main.js'))
  const loaded = f.plugin.load(id)
  assert.equal(typeof loaded.code, 'string')
  assert.ok(!loaded.code.includes('scalaHandler'), 'the stub does not contain the output')
  assert.doesNotMatch(loaded.code, /\bimport\b/, 'the stub needs no import resolution')
  assert.equal(typeof loaded.syntheticNamedExports, 'string')
  const stub = await import(pathToFileURL(await f.put('stub.mjs', loaded.code)).href)
  // h3 1.x recognizes an event handler by the marker that defineEventHandler sets.
  assert.equal(stub.default.__is_handler__, true)
  const fallback = stub[loaded.syntheticNamedExports]
  for (const name of ['handler', 'default', 'notesGet'])
    assert.equal(fallback[name], stub.default, `named import ${name}`)
  assert.equal(fallback.then, undefined, 'the fallback is not a thenable')
  assert.equal(fallback[Symbol.toPrimitive], undefined)
  assert.deepEqual(Object.keys(fallback), [])
  // claude 2026-10-19: created: an h3 1.x event always has `node.req` (review F5)
  assert.throws(() => stub.default({ node: { req: {} } }), (error) => {
    assert.equal(error.statusCode, 501)
    assert.match(error.statusMessage, /scalaPrerender: 'stub'/)
    // h3 sanitizes status messages to visible ASCII and tabs.
    assert.match(error.statusMessage, /^[\t\x20-\x7E]+$/)
    assert.match(error.message, /scalaPrerender/)
    // claude 2026-10-19: created: h3 1.x handles an error whose class has this marker: no "[unhandled]" log, and Nitro keeps its message (review F2)
    assert.ok(error instanceof Error)
    assert.equal(error.constructor.__h3_error__, true)
    assert.equal(error.unhandled, false)
    assert.equal(error.fatal, false)
    return true
  })
  assert.deepEqual(warnings, [
    '[h34s] GET (unknown path) answered 501: Scala.js is not bundled for prerendering (h34s scalaPrerender: \'stub\').',
  ])
})

// claude 2026-10-19: created: a call without an h3 event, as from a Nitro plugin at startup, names no route (review F5)
test('a call without an h3 event throws a message that names no route, and is recorded', async (t) => {
  const f = await fixture(t)
  const warnings = captureWarnings(t)
  const stub = await loadStub(f)
  t.after(takeStubCalls)
  recordStubCalls()
  for (const args of [[], [{}], [42, 'text']]) {
    assert.throws(() => stub.default(...args), (error) => {
      assert.equal(error.statusCode, 501)
      assert.equal(error.constructor.__h3_error__, true)
      assert.equal(error.unhandled, false)
      assert.match(error.message, /^Scala\.js code was called without an h3 event while Nuxt prerendered, for example by a Nitro plugin at startup\./)
      assert.doesNotMatch(error.message, /This route/)
      assert.match(error.message, /scalaPrerender is 'stub'/)
      return true
    }, JSON.stringify(args))
  }
  const outside = { method: undefined, path: undefined, page: undefined }
  assert.deepEqual(takeStubCalls(), [outside, outside, outside])
  const line = '[h34s] Scala.js code was called without an h3 event while Nuxt prerendered, for example by a Nitro plugin at startup: Scala.js is not bundled for prerendering (h34s scalaPrerender: \'stub\').'
  assert.deepEqual(warnings, [line, line, line])
  assert.deepEqual(stubCallReport([outside]).split('\n')[1], '  Scala.js code called without an h3 event, not as a route handler')
})

// claude 2026-10-19: created: query strings can hold tokens, so the record and the log keep the path only (Codex review on #55)
test('records and logs the path without its query string or fragment', async (t) => {
  const f = await fixture(t)
  const warnings = captureWarnings(t)
  const stub = await loadStub(f)
  t.after(takeStubCalls)
  recordStubCalls()
  const request = {
    method: 'GET',
    originalUrl: '/api/report?token=secret-value#part',
    url: '/report?token=secret-value',
    headers: { 'x-nitro-prerender': '/reports?session=secret-page' },
  }
  assert.throws(() => stub.default({ method: 'GET', path: '/report?token=secret-value', node: { req: request } }), { statusCode: 501 })
  const calls = takeStubCalls()
  assert.deepEqual(calls, [{ method: 'GET', path: '/api/report?…', page: '/reports?…' }])
  for (const text of [...warnings, stubCallReport(calls)])
    assert.doesNotMatch(text, /secret/)
})

// claude 2026-10-19: created: the stub records each request while the Nuxt module collects them (review F1)
test('records each request that reaches the stub while a record is open, and logs it', async (t) => {
  const f = await fixture(t)
  const warnings = captureWarnings(t)
  const stub = await loadStub(f)
  t.after(takeStubCalls)
  const event = (method, path, page) => ({
    method,
    path,
    node: { req: { method, url: path, headers: page ? { 'x-nitro-prerender': page } : {} } },
  })

  assert.throws(() => stub.default(event('GET', '/api/before')), { statusCode: 501 })
  assert.deepEqual(takeStubCalls(), [], 'nothing is recorded without an open record')

  recordStubCalls()
  assert.throws(() => stub.default(event('POST', '/api/notes', '/notes')), { statusCode: 501 })
  assert.throws(() => stub.default({ node: { req: { method: 'GET', url: '/api/raw' } } }), { statusCode: 501 })
  assert.deepEqual(takeStubCalls(), [
    { method: 'POST', path: '/api/notes', page: '/notes' },
    { method: 'GET', path: '/api/raw', page: undefined },
  ])
  assert.deepEqual(takeStubCalls(), [], 'taking the record closes it')
  assert.throws(() => stub.default(event('GET', '/api/after')), { statusCode: 501 })
  assert.deepEqual(takeStubCalls(), [])
  assert.deepEqual(warnings, [
    '[h34s] GET /api/before answered 501: Scala.js is not bundled for prerendering (h34s scalaPrerender: \'stub\').',
    '[h34s] POST /api/notes answered 501 while Nuxt prerendered /notes: Scala.js is not bundled for prerendering (h34s scalaPrerender: \'stub\').',
    '[h34s] GET /api/raw answered 501: Scala.js is not bundled for prerendering (h34s scalaPrerender: \'stub\').',
    '[h34s] GET /api/after answered 501: Scala.js is not bundled for prerendering (h34s scalaPrerender: \'stub\').',
  ])
})

// claude 2026-10-19: created: the build message lists each request once, sorted, and names the remedy (review F1)
test('reports the recorded requests once each, sorted, with the option and the remedy', () => {
  const report = stubCallReport([
    { method: 'GET', path: '/api/b', page: '/' },
    { method: 'GET', path: '/api/a', page: '/about' },
    { method: 'GET', path: '/api/b', page: '/' },
    { method: 'POST', path: '/api/c' },
  ])
  const lines = report.split('\n')
  assert.match(lines[0], /^@yuri4n\/h34s-nuxt: scalaPrerender is 'stub'/)
  assert.deepEqual(lines.slice(1, 4), [
    '  GET /api/a, while Nuxt prerendered /about',
    '  GET /api/b, while Nuxt prerendered /',
    '  POST /api/c',
  ])
  assert.match(lines[4], /as useFetch does/)
  // claude 2026-10-19: created: Nuxt shares prerendered data between pages, so one request can affect several pages (review F3)
  assert.match(lines[4], /Other pages that use the same data, for example through a shared layout or component, may hold the same error; check every prerendered page that renders a listed call\./)
  assert.match(lines[4], /remove scalaPrerender: 'stub'/)
  assert.equal(lines.length, 5)

  const many = Array.from({ length: 23 }, (_, i) => ({ method: 'GET', path: `/api/${String(i).padStart(2, '0')}` }))
  const limited = stubCallReport(many).split('\n')
  assert.equal(limited.length, 1 + 20 + 1 + 1)
  assert.equal(limited[20], '  GET /api/19')
  assert.equal(limited[21], '  and 3 more')
})
