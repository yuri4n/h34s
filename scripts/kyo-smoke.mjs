import assert from 'node:assert/strict'
import process from 'node:process'

const base = process.env.H34S_BASE_URL ?? 'http://127.0.0.1:3000'
const request = options => fetch(`${base}/api/kyo/greeting`, {
  signal: AbortSignal.timeout(15000), ...options,
})

const greeting = await request()
assert.equal(greeting.status, 200)
assert.deepEqual(await greeting.json(), { message: 'Hello, Kyo!' })

// Exercise native request decoding and application-owned Env provisioning in Nitro.
for (const name of ['Scala', 'Nuxt', 'Kyo again']) {
  const personalized = await request({
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ name }),
  })
  assert.equal(personalized.status, 200)
  assert.deepEqual(await personalized.json(), { message: `Hello, ${name}!` })
}

console.log('Passed: Kyo GET/POST, native models, and application service provisioning in Nitro')
