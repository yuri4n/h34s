import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, relative } from 'node:path'
// The package uses Node's runner without additional test dependencies.
// eslint-disable-next-line test/no-import-node-test
import test from 'node:test'
import { declaration, loadRecords, readRoutes, writeRoutes } from '../src/routes.mjs'

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'h34s-routes-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  mkdirSync(join(root, 'server/.scala'), { recursive: true })
  return root
}

const types = { success: '{ data: { message: string } }', errors: '{ errors: Array<{ message: string }> }' }

/** A linked module whose default export carries an h34s record, as `EventHandler.at` writes it. */
function linked(root, file, record, body = '') {
  writeFileSync(join(root, 'server/.scala', file), `${body}
const handler = () => 'ok'
${record ? `Object.defineProperty(handler, Symbol.for('h34s.handler'), { value: ${record} })` : ''}
export default handler
`)
}

test('the collector imports each linked module in a child process and reads the h34s records', async (t) => {
  const root = fixture(t)
  linked(root, 'greeting-get.js', `{ version: 1, route: '/api/greeting', method: 'get', types: () => (${JSON.stringify(types)}) }`)
  linked(root, 'plain.js', undefined)
  linked(root, 'unmounted.js', `{ version: 1, types: () => (${JSON.stringify(types)}) }`)
  writeFileSync(join(root, 'server/.scala/internal-1.js'), 'globalThis.h34sCollectorProbe = true\nexport const shared = 1\n')
  writeFileSync(join(root, 'server/.scala/notes.d.ts'), 'not a module')
  const records = await loadRecords(join(root, 'server/.scala'))
  assert.deepEqual(records, [
    { file: 'greeting-get.js', version: 1, route: '/api/greeting', method: 'get', ...types },
    { file: 'unmounted.js', version: 1, ...types },
  ])
  assert.equal(globalThis.h34sCollectorProbe, undefined, 'linked code runs in the child process only')
})

test('a module that fails to load names the module', async (t) => {
  const root = fixture(t)
  linked(root, 'broken.js', undefined, 'throw new Error(\'static initializer failed\')')
  await assert.rejects(loadRecords(join(root, 'server/.scala')), /linked Scala\.js module broken\.js: Error: static initializer failed/)
})

test('writes a declaration next to each typed module and the route manifest of each output', async (t) => {
  const root = fixture(t)
  const config = { modules: [{ output: 'server/.scala' }] }
  const records = [
    { file: 'greeting-get.js', version: 1, route: '/api/greeting', method: 'get', ...types },
    { file: 'any.js', version: 1, route: '/api/**', ...types },
    { file: 'unmounted.js', version: 1, ...types },
  ]
  const [output] = await writeRoutes({ root, config, load: async () => records })
  assert.deepEqual(output, {
    output: 'server/.scala',
    version: 1,
    routes: [
      { module: 'greeting-get.js', route: '/api/greeting', method: 'get' },
      { module: 'any.js', route: '/api/**' },
    ],
    declarations: ['any.d.ts', 'greeting-get.d.ts', 'unmounted.d.ts'],
  })
  const written = readFileSync(join(root, 'server/.scala/greeting-get.d.ts'), 'utf8')
  assert.equal(written, declaration(records[0]))
  assert.match(written, /mounted at GET \/api\/greeting/)
  assert.match(written, /export type SuccessBody = \{ data: \{ message: string \} \}/)
  assert.match(written, /export type ErrorBody = \{ errors: Array<\{ message: string \}> \}/)
  assert.match(written, /declare const handler: EventHandler<EventHandlerRequest, Promise<SuccessBody>>/)
  assert.deepEqual(JSON.parse(readFileSync(join(root, 'server/.scala/h34s-routes.json'), 'utf8')), {
    version: 1, routes: output.routes, declarations: output.declarations,
  })
  assert.deepEqual(readRoutes({ root, config }).handlers, [
    { route: '/api/greeting', method: 'get', lazy: true, handler: join(root, 'server/.scala/greeting-get.js') },
    { route: '/api/**', lazy: true, handler: join(root, 'server/.scala/any.js') },
  ])

  // The next link no longer has `any.js`: its declaration goes, other files stay.
  writeFileSync(join(root, 'server/.scala/own.d.ts'), 'kept')
  await writeRoutes({ root, config, load: async () => records.filter(record => record.file !== 'any.js') })
  assert.equal(existsSync(join(root, 'server/.scala/any.d.ts')), false)
  assert.equal(readFileSync(join(root, 'server/.scala/own.d.ts'), 'utf8'), 'kept')
})

test('rejects duplicate routes, invalid routes, other record versions and outputs without handlers', async (t) => {
  const root = fixture(t)
  mkdirSync(join(root, 'server/.scala-kyo'), { recursive: true })
  const route = { version: 1, route: '/api/x', method: 'get', ...types }
  const both = { modules: [{ output: 'server/.scala' }, { output: 'server/.scala-kyo' }] }
  await assert.rejects(
    writeRoutes({ root, config: both, load: async () => [{ file: 'a.js', ...route }] }),
    /Two Scala\.js handlers mount GET \/api\/x: server\/\.scala\/a\.js and server\/\.scala-kyo\/a\.js/,
  )
  const one = { modules: [{ output: 'server/.scala' }] }
  for (const [record, message] of [
    [{ ...route, route: 'api/x' }, /must start with '\/'/],
    [{ ...route, route: '/api//x' }, /empty segments/],
    [{ ...route, route: '/api/x?y' }, /must not contain/],
    [{ ...route, method: 'trace' }, /unsupported route method trace/],
    [{ ...route, version: 2 }, /record version 2/],
  ]) {
    await assert.rejects(writeRoutes({ root, config: one, load: async () => [{ file: 'a.js', ...record }] }), message)
  }
  await assert.rejects(writeRoutes({ root, config: one, load: async () => [] }), /has no h34s handlers/)
  // Listed entries are enough: an output may have no h34s handler (route files mount it).
  const [listed] = await writeRoutes({ root, config: { modules: [{ output: 'server/.scala', entries: ['main.js'] }] }, load: async () => [] })
  assert.deepEqual(listed.routes, [])
})

// claude 2026-10-19: created: a failing later output must not leave a mixed snapshot of manifests
test('a failure in a later output leaves the manifests and declarations of every output as they were', async (t) => {
  const root = fixture(t)
  mkdirSync(join(root, 'server/.scala-kyo'), { recursive: true })
  const config = { modules: [{ output: 'server/.scala' }, { output: 'server/.scala-kyo' }] }
  const first = { file: 'a.js', version: 1, route: '/api/a', method: 'get', ...types }
  const second = { file: 'b.js', version: 1, route: '/api/b', method: 'get', ...types }
  const linkedOutputs = { 'server/.scala': [first], 'server/.scala-kyo': [second] }
  await writeRoutes({ root, config, load: async directory => linkedOutputs[relative(root, directory)] })
  const files = ['server/.scala/h34s-routes.json', 'server/.scala/a.d.ts', 'server/.scala-kyo/h34s-routes.json', 'server/.scala-kyo/b.d.ts']
  const before = files.map(file => readFileSync(join(root, file), 'utf8'))

  // The next link changes the first output; the second fails in each way the collection can fail.
  const changed = [{ ...first, route: '/api/a2', success: 'string' }, { file: 'c.js', version: 1, route: '/api/c', ...types }]
  for (const [failure, load, message] of [
    ['a module that fails to load', async () => { throw new Error('Could not load the linked Scala.js module b.js') }, /Could not load/],
    ['a duplicate route', async () => [{ ...second, route: '/api/a2' }], /Two Scala\.js handlers mount GET \/api\/a2/],
    ['an invalid route', async () => [{ ...second, route: 'api/b' }], /must start with '\/'/],
    ['no handlers', async () => [], /has no h34s handlers/],
  ]) {
    await assert.rejects(
      writeRoutes({ root, config, load: async directory => relative(root, directory) === 'server/.scala' ? changed : load() }),
      message,
      failure,
    )
    assert.deepEqual(files.map(file => readFileSync(join(root, file), 'utf8')), before, failure)
    assert.equal(existsSync(join(root, 'server/.scala/c.d.ts')), false, failure)
  }
  assert.deepEqual(readRoutes({ root, config }).handlers.map(handler => handler.route), ['/api/a', '/api/b'])
})

test('reports outputs that have no manifest yet', (t) => {
  const root = fixture(t)
  assert.deepEqual(readRoutes({ root, config: { modules: [{ output: 'server/.scala' }] } }), {
    handlers: [],
    missing: ['server/.scala'],
  })
})
