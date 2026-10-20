// claude 2026-10-19: created: scalaPrerenderStub in a real Rollup build and a real h3 app (issue #30)
import assert from 'node:assert/strict'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { createServer } from 'node:http'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
// These build-tool checks run directly in Node without the Nuxt/Vitest runtime.
// eslint-disable-next-line test/no-import-node-test
import test from 'node:test'
import { pathToFileURL } from 'node:url'
import { scalaPrerenderStub } from '@yuri4n/h34s-nuxt/rollup'
// claude 2026-10-19: created: the request record that the Nuxt module opens for one prerender run (review F1)
import { recordStubCalls, takeStubCalls } from '../packages/nuxt/src/scala-prerender-stub.mjs'

// The Rollup and h3 installations that this application's Nitro uses.
const nuxtRequire = createRequire(import.meta.resolve('nuxt'))
const nitroRequire = createRequire(nuxtRequire.resolve('nitropack/package.json'))
const { rollup } = await import(pathToFileURL(nitroRequire.resolve('rollup')).href)
const h3 = await import(pathToFileURL(nitroRequire.resolve('h3')).href)
// claude 2026-10-19: created: Nitro's production error handler and the ofetch that Nuxt's useFetch uses (review F2)
const nitroDirectory = dirname(nuxtRequire.resolve('nitropack/package.json'))
const { default: nitroErrorHandler } = await import(pathToFileURL(join(nitroDirectory, 'dist/runtime/internal/error/prod.mjs')).href)
const { ofetch } = await import(pathToFileURL(nitroRequire.resolve('ofetch')).href)

const linkedMarkers = ['h34s linked ZIO marker', 'h34s linked internal marker', 'h34s linked Kyo marker']

async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'h34s-prerender-stub-'))
  t.after(() => rm(root, { recursive: true, force: true }))
  async function put(path, content) {
    const file = join(root, path)
    await mkdir(dirname(file), { recursive: true })
    await writeFile(file, content)
    return file
  }
  await put('package.json', '{"type":"module"}')
  // Linked Scala.js output: an entry with an internal chunk, and a named export.
  await put('server/.scala/notes-get.js', `import { internal } from './internal.js'\nexport default function notesGet() { return ${JSON.stringify(linkedMarkers[0])} + internal }\n`)
  await put('server/.scala/internal.js', `export const internal = ${JSON.stringify(linkedMarkers[1])}\n`)
  await put('server/.scala-kyo/kyo.js', `export function handler() { return ${JSON.stringify(linkedMarkers[2])} }\n`)
  // Route files that mount them, and a lazy handler list in the style of Nitro's.
  await put('server/api/notes.get.js', 'export { default } from \'../.scala/notes-get\'\n')
  await put('server/api/kyo.get.js', 'export { handler as default } from \'../.scala-kyo/kyo.js\'\n')
  const entry = await put('server/entry.js', 'export const handlers = { notes: () => import(\'./api/notes.get.js\'), kyo: () => import(\'./api/kyo.get.js\') }\n')
  return { root, entry }
}

async function bundle(t, f, plugins) {
  const build = await rollup({ input: f.entry, plugins })
  t.after(() => build.close())
  const { output } = await build.generate({ format: 'es', dir: join(f.root, 'out') })
  return output
}

test('Rollup bundles the linked Scala.js without the stub plugin (control)', async (t) => {
  const f = await fixture(t)
  const code = (await bundle(t, f, [])).map(chunk => chunk.code).join('\n')
  for (const marker of linkedMarkers)
    assert.ok(code.includes(marker), marker)
})

test('with scalaPrerenderStub, the bundle holds the stub instead of the linked Scala.js, and h3 answers 501', async (t) => {
  const f = await fixture(t)
  const stub = scalaPrerenderStub(f.root)
  const output = await bundle(t, f, [
    {
      name: 'ordinary-resolver',
      resolveId(source) {
        assert.ok(!/\.scala/.test(source), `the stub must resolve ${source} before ordinary resolvers`)
        return null
      },
      load(id) {
        assert.ok(!/\.scala/.test(id), `linked code must never enter Rollup: ${id}`)
        return null
      },
    },
    stub,
  ])
  const code = output.map(chunk => chunk.code).join('\n')
  for (const marker of linkedMarkers)
    assert.ok(!code.includes(marker), `${marker} must not be bundled`)
  assert.match(code, /scalaPrerender: 'stub'/)
  await mkdir(join(f.root, 'out'))
  for (const chunk of output)
    await writeFile(join(f.root, 'out', chunk.fileName), chunk.code)
  const { handlers } = await import(pathToFileURL(join(f.root, 'out/entry.js')).href)

  const warnings = []
  const warn = console.warn
  console.warn = (...args) => warnings.push(args.join(' '))
  // claude 2026-10-19: created: h3 and Nitro log an unhandled error with console.error (review F2)
  const consoleErrors = []
  const error = console.error
  console.error = (...args) => consoleErrors.push(args.map(String).join(' '))
  t.after(() => {
    console.warn = warn
    console.error = error // claude 2026-10-19: created
  })
  // claude 2026-10-19: created: h3 passes errors to Nitro's production error handler, as a Nitro app does (review F2)
  const errors = []
  const app = h3.createApp({
    onError: (error, event) => {
      errors.push(error)
      return nitroErrorHandler(error, event)
    },
  })
  app.use('/notes', (await handlers.notes()).default)
  app.use('/kyo', (await handlers.kyo()).default)
  const server = createServer(h3.toNodeListener(app))
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  t.after(() => new Promise(resolve => server.close(resolve)))
  // claude 2026-10-19: created: real h3 events give the stub's record the method, path and prerendered page (review F1)
  recordStubCalls()
  t.after(takeStubCalls)
  const url = path => `http://127.0.0.1:${server.address().port}${path}`
  for (const [path, headers] of [['/notes', { 'x-nitro-prerender': '/notes-page' }], ['/kyo', {}]]) {
    const response = await fetch(url(path), { headers })
    assert.equal(response.status, 501, path)
    assert.match(response.statusText, /scalaPrerender: 'stub'/, path)
    const body = await response.text()
    assert.ok(!linkedMarkers.some(marker => body.includes(marker)), body)
    // claude 2026-10-19: created: Nitro answers with the stub's own message, not the "Server Error" of an unhandled error (review F2)
    const json = JSON.parse(body)
    assert.equal(json.statusCode, 501, body)
    assert.match(json.statusMessage, /scalaPrerender: 'stub'/, body)
    assert.match(json.message, /leaves Scala\.js out of Nitro's prerender bundle/, body)
  }
  // claude 2026-10-19: created: ofetch, which useFetch uses, still rejects with the 501 (review F2)
  await assert.rejects(ofetch(url('/notes'), { headers: { 'x-nitro-prerender': '/other-page' } }), { statusCode: 501 })
  assert.deepEqual(warnings.filter(warning => warning.includes('[h3]')), [], 'h3 recognizes the stub as an event handler')
  // claude 2026-10-19: created: h3 and Nitro treat the stub's 501 as a handled error, so neither logs it as unhandled (review F2)
  assert.equal(errors.length, 3)
  for (const handled of errors) {
    assert.equal(handled.statusCode, 501)
    assert.equal(handled.unhandled, false, 'h3 does not mark the stub error as unhandled')
    assert.equal(handled.fatal, false)
  }
  assert.deepEqual(consoleErrors, [], 'neither h3 nor Nitro logs an unhandled error')
  assert.deepEqual(takeStubCalls(), [
    { method: 'GET', path: '/notes', page: '/notes-page' },
    { method: 'GET', path: '/kyo', page: undefined },
    { method: 'GET', path: '/notes', page: '/other-page' }, // claude 2026-10-19: created: the ofetch request
  ])
  assert.deepEqual(warnings.filter(warning => warning.startsWith('[h34s]')), [
    '[h34s] GET /notes answered 501 while Nuxt prerendered /notes-page: Scala.js is not bundled for prerendering (h34s scalaPrerender: \'stub\').',
    '[h34s] GET /kyo answered 501: Scala.js is not bundled for prerendering (h34s scalaPrerender: \'stub\').',
    '[h34s] GET /notes answered 501 while Nuxt prerendered /other-page: Scala.js is not bundled for prerendering (h34s scalaPrerender: \'stub\').', // claude 2026-10-19: created: the ofetch request
  ])
  assert.ok(!(await readFile(join(f.root, 'out/entry.js'), 'utf8')).includes('.scala'))
})
