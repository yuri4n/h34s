import assert from 'node:assert/strict'
import process from 'node:process'

// HTTP smoke check of the ZIO example against a running Nuxt server and its PostgreSQL database.
// Without PostgreSQL, set H34S_SMOKE_SKIP_DATABASE=true: the input checks still run.
// Success bodies are `{ data: ... }`; errors are `{ errors: [{ message, path? }] }`.
const base = process.env.H34S_BASE_URL ?? 'http://127.0.0.1:3000'
function request(path, options) {
  return fetch(
    `${base}${path}`,
    { signal: AbortSignal.timeout(15000), ...options },
  )
}
function post(path, body) {
  return request(path, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body,
  })
}

// A streamed body is sent as is, without the envelope.
const stream = await request('/api/test')
assert.equal(stream.status, 200)
assert.equal(await stream.text(), 'abc')

// claude 2026-10-20: created: the countdown routes send server-sent events (ADR-0007)
// Each countdown sends four tick events, one per second, with h3's event-stream headers.
for (const runtime of ['kyo', 'zio']) {
  const events = await request(`/api/${runtime}/countdown`)
  assert.equal(events.status, 200)
  assert.equal(events.headers.get('content-type'), 'text/event-stream')
  assert.equal(
    events.headers.get('cache-control'),
    'private, no-cache, no-store, no-transform, must-revalidate, max-age=0',
  )
  assert.equal(events.headers.get('x-accel-buffering'), 'no')
  assert.equal(
    await events.text(),
    [3, 2, 1, 0]
      .map(remaining => `event: tick\ndata: {"remaining":${remaining}}\n\n`)
      .join(''),
  )
}

// claude 2026-10-20: created: the ZIO greeting needs no database (issue #52)
// The ZIO greeting answers the Kyo greeting's shape and never builds the database pool.
const zioGreeting = await post('/api/zio/greeting', JSON.stringify({ name: 'Scala' }))
assert.equal(zioGreeting.status, 200)
assert.equal(zioGreeting.headers.get('cache-control'), 'no-store')
assert.deepEqual(await zioGreeting.json(), { data: { message: 'Hello, Scala!' } })
const blankName = await post('/api/zio/greeting', JSON.stringify({ name: '  ' }))
assert.equal(blankName.status, 422)
assert.deepEqual(
  await blankName.json(),
  { errors: [{ message: 'must have at least 1 character', path: 'name' }] },
)

// Input errors come before any database work, so these checks also pass without PostgreSQL.
// The model schema trims, then checks the length: one entry with the field path.
for (const [value, message] of [
  [' ', 'must have at least 1 character'],
  ['a'.repeat(2001), 'must have at most 2000 characters'],
]) {
  const invalid = await post('/api/notes', JSON.stringify({ text: value }))
  assert.equal(invalid.status, 422)
  assert.deepEqual(await invalid.json(), { errors: [{ message, path: 'text' }] })
}

// A value of the wrong shape gets one generic entry, never the input.
for (const body of [
  { text: null },
  { text: 42 },
  {},
  null,
  [],
  true,
  { text: {} },
]) {
  const malformed = await post('/api/notes', JSON.stringify(body))
  assert.equal(malformed.status, 422)
  assert.deepEqual(
    await malformed.json(),
    { errors: [{ message: 'Request body does not match the expected shape' }] },
  )
}

const unparsable = await post('/api/notes', '{"text":')
assert.equal(unparsable.status, 400)
assert.equal((await unparsable.json()).errors.length, 1)

// Set H34S_SMOKE_SKIP_DATABASE=true to run only the checks above, without PostgreSQL.
const database = process.env.H34S_SMOKE_SKIP_DATABASE !== 'true'
if (database) {
  const text = `h34s smoke ${crypto.randomUUID()}`
  const created = await post('/api/notes', JSON.stringify({ text: `  ${text}  ` }))
  assert.equal(created.status, 201)
  assert.equal(created.headers.get('cache-control'), 'no-store')
  const { data: note } = await created.json()
  assert.equal(note.text, text)
  assert.ok(Number.isInteger(note.id))

  const listed = await request('/api/notes')
  assert.equal(listed.status, 200)
  assert.ok(
    (await listed.json()).data.some(item => item.id === note.id && item.text === text),
  )
}

// claude 2026-10-20: changed: the message names the server-sent events check (ADR-0007)
console.log(
  database
    ? 'Passed: stream response, server-sent events, ZIO greeting, schema validation entries, malformed-input responses and database insert/read'
    : 'Passed: stream response, server-sent events, ZIO greeting, schema validation entries and malformed-input responses (database skipped)',
)
