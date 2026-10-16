import assert from 'node:assert/strict'
import process from 'node:process'

const base = process.env.H34S_BASE_URL ?? 'http://127.0.0.1:3000'
function request(path, options) {
  return fetch(
    `${base}${path}`,
    { signal: AbortSignal.timeout(15000), ...options },
  )
}

const stream = await request('/api/test')
assert.equal(stream.status, 200)
assert.equal(await stream.text(), 'abc')

const text = `h34s smoke ${crypto.randomUUID()}`
const created = await request('/api/notes', {
  method: 'POST',
  headers: {
    'content-type': 'application/json',
  },
  body: JSON.stringify({ text: `  ${text}  ` }),
})
assert.equal(created.status, 201)
const note = await created.json()
assert.equal(note.text, text)
assert.ok(Number.isInteger(note.id))

const listed = await request('/api/notes')
assert.equal(listed.status, 200)
assert.ok(
  (await listed.json()).some(item => item.id === note.id && item.text === text),
)

const invalid = await request('/api/notes', {
  method: 'POST',
  headers: {
    'content-type': 'application/json',
  },
  body: JSON.stringify({ text: ' ' }),
})
assert.equal(invalid.status, 422)
assert.deepEqual(
  await invalid.json(),
  { errors: ['Request body does not match schema'] },
)
for (const body of [
  { text: null },
  { text: 42 },
  {},
  null,
  [],
  true,
  { text: {} },
]) {
  const malformed = await request('/api/notes', {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
    },
    body: JSON.stringify(body),
  })
  assert.equal(malformed.status, 422)
  assert.deepEqual(
    await malformed.json(),
    { errors: ['Request body does not match schema'] },
  )
}
console.log(
  'Passed: stream response, database insert/read, safe validation and malformed-input responses',
)
