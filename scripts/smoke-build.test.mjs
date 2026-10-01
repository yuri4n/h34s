import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { createServer } from 'node:net'
import test from 'node:test'
import { setTimeout as delay } from 'node:timers/promises'

async function availablePort() {
  const socket = createServer()
  socket.listen(0, '127.0.0.1')
  await once(socket, 'listening')
  const { port } = socket.address()
  await new Promise((resolve, reject) => socket.close(error => error ? reject(error) : resolve()))
  return port
}

test('the production Nitro server serves the linked Scala stream', { timeout: 30_000 }, async (t) => {
  const port = await availablePort()
  const server = spawn(process.execPath, ['.output/server/index.mjs'], {
    env: {
      PATH: process.env.PATH,
      HOME: process.env.HOME,
      NODE_ENV: 'production',
      NITRO_HOST: '127.0.0.1',
      NITRO_PORT: String(port),
      NUXT_TELEMETRY_DISABLED: '1',
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  let output = ''
  let spawnError
  server.on('error', error => { spawnError = error })
  server.stdout.on('data', chunk => { output += chunk })
  server.stderr.on('data', chunk => { output += chunk })
  t.after(async () => {
    if (server.exitCode !== null || server.signalCode !== null || spawnError) return
    const exited = once(server, 'exit')
    server.kill('SIGTERM')
    const forceExit = setTimeout(() => server.kill('SIGKILL'), 3_000)
    try { await exited } finally { clearTimeout(forceExit) }
  })

  const deadline = Date.now() + 20_000
  let response
  while (Date.now() < deadline && !t.signal.aborted) {
    assert.ifError(spawnError)
    assert.equal(server.exitCode, null, `Nitro exited before the request:\n${output}`)
    try {
      response = await fetch(`http://127.0.0.1:${port}/api/test`, {
        signal: AbortSignal.any([t.signal, AbortSignal.timeout(2_000)]),
      })
      break
    }
    catch {
      await delay(100, undefined, { signal: t.signal })
    }
  }
  assert.ok(response, `Nitro did not become ready:\n${output}`)
  assert.equal(response.status, 200, `Unexpected HTTP status:\n${output}`)
  assert.equal(await response.text(), 'abc')
})
