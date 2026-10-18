import assert from 'node:assert/strict'
import process from 'node:process'

// HTTP smoke check of the Kyo example against a running Nuxt server. No database is needed.
// Success bodies are `{ data: ... }`; errors are `{ errors: [{ message, path? }] }`.
const base = process.env.H34S_BASE_URL ?? 'http://127.0.0.1:3000'
// The example reads its salutation from runtimeConfig.greeting with RuntimeConfig.load. Start the
// server with NUXT_GREETING_SALUTATION=Hi and run this check with H34S_EXPECTED_SALUTATION=Hi to
// check the NUXT_* override.
const salutation = process.env.H34S_EXPECTED_SALUTATION ?? 'Hello'
const request = options => fetch(`${base}/api/kyo/greeting`, {
  signal: AbortSignal.timeout(15000), ...options,
})
const post = body => request({
  method: 'POST',
  headers: { 'content-type': 'application/json' },
  body,
})

const greeting = await request()
assert.equal(greeting.status, 200)
// The example's AppHandler sets this default on every response.
assert.equal(greeting.headers.get('cache-control'), 'no-store')
assert.deepEqual(await greeting.json(), { data: { message: `${salutation}, Kyo!` } })

// Exercise native request decoding and application-owned Env provisioning in Nitro.
for (const name of ['Scala', 'Nuxt', 'Kyo again']) {
  const personalized = await post(JSON.stringify({ name }))
  assert.equal(personalized.status, 200)
  assert.deepEqual(await personalized.json(), { data: { message: `${salutation}, ${name}!` } })
}

// The model's one Kyo schema validates the name: one entry with the field path.
for (const name of [' ', 'a'.repeat(81)]) {
  const invalid = await post(JSON.stringify({ name }))
  assert.equal(invalid.status, 422)
  assert.equal(invalid.headers.get('cache-control'), 'no-store')
  const { errors } = await invalid.json()
  assert.equal(errors.length, 1)
  assert.equal(errors[0].path, 'name')
  assert.equal(typeof errors[0].message, 'string')
  if (name.trim())
    assert.ok(!errors[0].message.includes(name), 'Error messages never repeat the input')
}

const wrongShape = await post('{"name":42}')
assert.equal(wrongShape.status, 422)
assert.deepEqual(await wrongShape.json(), { errors: [{ message: 'Request body does not match the expected shape' }] })

const unparsable = await post('{"name":')
assert.equal(unparsable.status, 400)
assert.equal((await unparsable.json()).errors.length, 1)

// An absent body is a shape failure (422), never a 500 or a hang.
const empty = await request({ method: 'POST', headers: { 'content-type': 'application/json' } })
assert.equal(empty.status, 422)

console.log(`Passed: Kyo GET/POST with the "${salutation}" runtime config salutation, the data envelope, schema validation entries, and application service provisioning in Nitro`)
