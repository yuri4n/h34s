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

test('loads a stub whose exports are h3 event handlers that answer 501 and name the option', async (t) => {
  const f = await fixture(t)
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
  assert.throws(() => stub.default({}), (error) => {
    assert.equal(error.statusCode, 501)
    assert.match(error.statusMessage, /scalaPrerender: 'stub'/)
    // h3 sanitizes status messages to visible ASCII and tabs.
    assert.match(error.statusMessage, /^[\t\x20-\x7E]+$/)
    assert.match(error.message, /scalaPrerender/)
    return true
  })
})
