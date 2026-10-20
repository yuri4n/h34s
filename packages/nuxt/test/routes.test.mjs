import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, relative } from 'node:path'
// The package uses Node's runner without additional test dependencies.
// eslint-disable-next-line test/no-import-node-test
import test from 'node:test'
import { declaration, loadRecords, modelsDeclaration, readRoutes, writeRoutes } from '../src/routes.mjs'

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

// claude 2026-10-20: changed: a record with named types passes them through (ADR-0014)
test('the collector imports each linked module in a child process and reads the h34s records', async (t) => {
  const root = fixture(t)
  const named = { ...types, named: { success: '{ data: Note }', uses: ['Note'], models: [{ name: 'Note', type: '{ text: string }', uses: [], scala: 'app.Note' }] } }
  linked(root, 'greeting-get.js', `{ version: 1, route: '/api/greeting', method: 'get', types: () => (${JSON.stringify(types)}) }`)
  linked(root, 'note-get.js', `{ version: 1, route: '/api/note', method: 'get', types: () => (${JSON.stringify(named)}) }`)
  linked(root, 'plain.js', undefined)
  linked(root, 'unmounted.js', `{ version: 1, types: () => (${JSON.stringify(types)}) }`)
  writeFileSync(join(root, 'server/.scala/internal-1.js'), 'globalThis.h34sCollectorProbe = true\nexport const shared = 1\n')
  writeFileSync(join(root, 'server/.scala/notes.d.ts'), 'not a module')
  const records = await loadRecords(join(root, 'server/.scala'))
  assert.deepEqual(records, [
    { file: 'greeting-get.js', version: 1, route: '/api/greeting', method: 'get', ...types },
    { file: 'note-get.js', version: 1, route: '/api/note', method: 'get', ...named },
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
  // claude 2026-10-20: changed: the manifest lists the models file (ADR-0014)
  assert.deepEqual(output, {
    output: 'server/.scala',
    version: 1,
    routes: [
      { module: 'greeting-get.js', route: '/api/greeting', method: 'get' },
      { module: 'any.js', route: '/api/**' },
    ],
    declarations: ['any.d.ts', 'greeting-get.d.ts', 'unmounted.d.ts'],
    models: { file: 'h34s-models.d.ts', names: [] },
  })
  const written = readFileSync(join(root, 'server/.scala/greeting-get.d.ts'), 'utf8')
  assert.equal(written, declaration(records[0]))
  assert.match(written, /mounted at GET \/api\/greeting/)
  assert.match(written, /export type SuccessBody = \{ data: \{ message: string \} \}/)
  assert.match(written, /export type ErrorBody = \{ errors: Array<\{ message: string \}> \}/)
  assert.match(written, /declare const handler: EventHandler<EventHandlerRequest, Promise<SuccessBody>>/)
  assert.deepEqual(JSON.parse(readFileSync(join(root, 'server/.scala/h34s-routes.json'), 'utf8')), {
    version: 1, routes: output.routes, declarations: output.declarations, models: output.models,
  })
  // claude 2026-10-20: created: without named models, the models file is an empty module (ADR-0014)
  assert.equal(readFileSync(join(root, 'server/.scala/h34s-models.d.ts'), 'utf8'), modelsDeclaration([]))
  assert.match(modelsDeclaration([]), /\nexport \{\}\n$/)
  assert.doesNotMatch(written, /h34s-models/)
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
  // claude 2026-10-20: changed: the models files stay as they were too (ADR-0014)
  const files = ['server/.scala/h34s-routes.json', 'server/.scala/a.d.ts', 'server/.scala/h34s-models.d.ts', 'server/.scala-kyo/h34s-routes.json', 'server/.scala-kyo/b.d.ts', 'server/.scala-kyo/h34s-models.d.ts']
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

// ---- Named models (ADR-0014) ----

const note = { name: 'Note', type: '{ id: number; text: string }', uses: [], scala: 'app.Note' }
const board = { name: 'Board', type: '{ notes: Array<Note>; pinned: Note | null }', uses: ['Note'], scala: 'app.Board' }

/** A route record with named types, as `HandlerMetadata` gives it. */
function routeWith(file, route, named, success = 'inline') {
  return { file, version: 1, route, method: 'get', success, errors: types.errors, named }
}

// claude 2026-10-20: created: the models file, the imports of each route and the manifest (ADR-0014)
test('writes each model once into h34s-models.d.ts in dependency order, and each route imports the names it uses', async (t) => {
  const root = fixture(t)
  const config = { modules: [{ output: 'server/.scala' }] }
  const records = [
    routeWith('board-get.js', '/api/board', { success: '{ data: Board }', uses: ['Board'], models: [note, board] }),
    routeWith('notes-get.js', '/api/notes', { success: '{ data: Array<Note> }', uses: ['Note'], models: [{ ...note, scala: 'other.Note' }] }),
    routeWith('count-get.js', '/api/count', { success: '{ data: number }', uses: [], models: [] }),
  ]
  const [output] = await writeRoutes({ root, config, load: async () => records })
  assert.deepEqual(output.models, { file: 'h34s-models.d.ts', names: ['Note', 'Board'] })
  const models = readFileSync(join(root, 'server/.scala/h34s-models.d.ts'), 'utf8')
  assert.equal(models, `// Generated by h34s-scala from the Scala.js modules of this directory. Do not edit.
// The models that the routes of this directory return. Import them in your app:
// import type { Name } from '#h34s/models'

/** The Scala type \`app.Note\`. */
export type Note = { id: number; text: string }

/** The Scala type \`app.Board\`. */
export type Board = { notes: Array<Note>; pinned: Note | null }
`)
  // The same name and structure from another Scala type is one declaration.
  assert.equal(models.match(/export type Note /g).length, 1)
  const boardRoute = readFileSync(join(root, 'server/.scala/board-get.d.ts'), 'utf8')
  assert.match(boardRoute, /^import type \{ Board \} from '\.\/h34s-models'$/m)
  assert.match(boardRoute, /export type SuccessBody = \{ data: Board \}/)
  assert.match(readFileSync(join(root, 'server/.scala/notes-get.d.ts'), 'utf8'), /^import type \{ Note \} from '\.\/h34s-models'\n[\s\S]*export type SuccessBody = \{ data: Array<Note> \}/m)
  const count = readFileSync(join(root, 'server/.scala/count-get.d.ts'), 'utf8')
  assert.doesNotMatch(count, /h34s-models/)
  assert.match(count, /export type SuccessBody = \{ data: number \}/)
})

// claude 2026-10-20: created: a generic ZIO record is written out, with the models that use it (ADR-0014)
test('one Scala type with different structures is a generic: it and the models that use it are written out', async (t) => {
  const root = fixture(t)
  const config = { modules: [{ output: 'server/.scala' }] }
  const page = type => ({ name: 'Page', type, uses: ['Note'], scala: 'app.Page' })
  const shelf = { name: 'Shelf', type: '{ page: Page }', uses: ['Page'], scala: 'app.Shelf' }
  const records = [
    routeWith('notes-get.js', '/api/notes', { success: '{ data: Page }', uses: ['Page'], models: [note, page('{ items: Array<Note> }')] }, '{ data: { items: Array<{ id: number; text: string }> } }'),
    routeWith('shelf-get.js', '/api/shelf', { success: '{ data: Shelf }', uses: ['Shelf'], models: [note, page('{ items: Array<Note>; total: number }'), shelf] }, '{ data: { page: { items: Array<{ id: number; text: string }>; total: number } } }'),
    routeWith('note-get.js', '/api/note', { success: '{ data: Note }', uses: ['Note'], models: [note] }),
  ]
  const [output] = await writeRoutes({ root, config, load: async () => records })
  assert.deepEqual(output.models.names, ['Note'])
  const notes = readFileSync(join(root, 'server/.scala/notes-get.d.ts'), 'utf8')
  assert.match(notes, /export type SuccessBody = \{ data: \{ items: Array<\{ id: number; text: string \}> \} \}/)
  assert.doesNotMatch(notes, /h34s-models/)
  assert.doesNotMatch(readFileSync(join(root, 'server/.scala/shelf-get.d.ts'), 'utf8'), /h34s-models|Shelf/)
  assert.match(readFileSync(join(root, 'server/.scala/note-get.d.ts'), 'utf8'), /import type \{ Note \}/)
})

// claude 2026-10-20: created: #h34s/models exports every output's models, so a name means one structure across outputs (ADR-0014)
test('a name with different structures in two outputs stops the generation; the same structure is allowed', async (t) => {
  const root = fixture(t)
  mkdirSync(join(root, 'server/.scala-kyo'), { recursive: true })
  const config = { modules: [{ output: 'server/.scala' }, { output: 'server/.scala-kyo' }] }
  const kyoNote = { name: 'Note', type: '{ body: string }', uses: [], scala: 'kyo.Note' }
  const byOutput = (zio, kyo) => async directory => relative(root, directory) === 'server/.scala' ? zio : kyo
  const zio = [routeWith('notes-get.js', '/api/notes', { success: '{ data: Note }', uses: ['Note'], models: [note] })]
  await assert.rejects(
    writeRoutes({ root, config, load: byOutput(zio, [routeWith('memo-get.js', '/api/memo', { success: '{ data: Note }', uses: ['Note'], models: [kyoNote] })]) }),
    /#h34s\/models exports the models of every output: two Scala types have the TypeScript name Note and different structures: app\.Note \(used by server\/\.scala: GET \/api\/notes\) and kyo\.Note \(used by server\/\.scala-kyo: GET \/api\/memo\)/,
  )
  const results = await writeRoutes({ root, config, load: byOutput(zio, [routeWith('memo-get.js', '/api/memo', { success: '{ data: Note }', uses: ['Note'], models: [{ ...note, scala: 'kyo.Note' }] })]) })
  assert.deepEqual(results.map(result => result.models.names), [['Note'], ['Note']])
})

// claude 2026-10-20: created: invalid named types and the reserved module ID (ADR-0014)
test('rejects a module named like the models file and named types without their shape', async (t) => {
  const root = fixture(t)
  const config = { modules: [{ output: 'server/.scala' }] }
  for (const [record, message] of [
    [{ file: 'h34s-models.js', version: 1, ...types }, /h34s-models\.js: the module ID h34s-models is reserved for the models file/],
    [routeWith('a.js', '/api/a', { success: 'Note', uses: 'Note', models: [] }), /a\.js: its named types are not \{ success, uses, models \}/],
    [routeWith('a.js', '/api/a', { success: 'Note', uses: [], models: [{ name: 'my-note', type: 'string', uses: [] }] }), /its model "my-note" is not/],
    [routeWith('a.js', '/api/a', { success: 'Note', uses: [], models: [{ ...note, scala: 1 }] }), /its model Note has a Scala name that is not a string/],
  ]) {
    await assert.rejects(writeRoutes({ root, config, load: async () => [record] }), message)
  }
})

// claude 2026-10-20: created: the negative control of the model clash check (ADR-0014)
test('two Scala types with one TypeScript name and different structures stop the generation', async (t) => {
  const root = fixture(t)
  const config = { modules: [{ output: 'server/.scala' }] }
  const note = (scala, type) => ({ name: 'Note', type, scala, uses: [] })
  const records = [
    { file: 'notes-get.js', version: 1, route: '/api/notes', method: 'get', ...types, named: { success: '{ data: Array<Note> }', uses: ['Note'], models: [note('app.notes.Note', '{ id: number; text: string }')] } },
    { file: 'memo-get.js', version: 1, route: '/api/memo', method: 'get', ...types, named: { success: '{ data: Note }', uses: ['Note'], models: [note('app.memo.Note', '{ body: string }')] } },
  ]
  await assert.rejects(
    writeRoutes({ root, config, load: async () => records }),
    /server\/\.scala: two Scala types have the TypeScript name Note and different structures: app\.notes\.Note \(used by GET \/api\/notes\) and app\.memo\.Note \(used by GET \/api\/memo\)/,
  )
  assert.equal(existsSync(join(root, 'server/.scala/h34s-routes.json')), false, 'nothing is written')
})

test('reports outputs that have no manifest yet', (t) => {
  const root = fixture(t)
  assert.deepEqual(readRoutes({ root, config: { modules: [{ output: 'server/.scala' }] } }), {
    handlers: [],
    missing: ['server/.scala'],
  })
})
