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

// Changed by Claude Code on 2026-10-13: the server start-up moved out of the first test so that
// the failing-stream test below can reuse it. Each test starts its own Nitro process.
async function startNitro(t) {
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
  const state = { output: '', spawnError: undefined }
  server.on('error', error => { state.spawnError = error })
  server.stdout.on('data', chunk => { state.output += chunk })
  server.stderr.on('data', chunk => { state.output += chunk })
  t.after(async () => {
    if (server.exitCode !== null || server.signalCode !== null || state.spawnError) return
    const exited = once(server, 'exit')
    server.kill('SIGTERM')
    const forceExit = setTimeout(() => server.kill('SIGKILL'), 3_000)
    try { await exited } finally { clearTimeout(forceExit) }
  })

  const deadline = Date.now() + 20_000
  let response
  while (Date.now() < deadline && !t.signal.aborted) {
    assert.ifError(state.spawnError)
    assert.equal(server.exitCode, null, `Nitro exited before the request:\n${state.output}`)
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
  assert.ok(response, `Nitro did not become ready:\n${state.output}`)
  return { port, server, state, firstResponse: response }
}

test('the production Nitro server serves the linked Scala stream', { timeout: 30_000 }, async (t) => {
  const { firstResponse: response, state } = await startNitro(t)
  assert.equal(response.status, 200, `Unexpected HTTP status:\n${state.output}`)
  assert.equal(await response.text(), 'abc')
})

// Created by Claude Code on 2026-10-13. Before the stream-boundary fix, a source that failed after
// its first chunk left the response open: the client received the first chunks and then waited for
// the connection to close. The server must now end that response, and it must keep serving.
test('a stream that fails after its first chunk ends the response and the server keeps serving', { timeout: 30_000 }, async (t) => {
  const { port, state } = await startNitro(t)
  const signal = AbortSignal.any([t.signal, AbortSignal.timeout(5_000)])

  const failing = await fetch(`http://127.0.0.1:${port}/api/test-stream-failure`, { signal })
  assert.equal(failing.status, 200, `The headers must go out with the first chunk:\n${state.output}`)
  await assert.rejects(
    failing.text(),
    // A TimeoutError here means the old behavior: the response never ended. undici reports a
    // body that ended without its final chunk as a TypeError ("terminated").
    error => error.name === 'TypeError',
    `The body must end with a transport error, not complete or time out:\n${state.output}`,
  )

  const healthy = await fetch(`http://127.0.0.1:${port}/api/test`, { signal })
  assert.equal(healthy.status, 200, `The server must keep serving after the failure:\n${state.output}`)
  assert.equal(await healthy.text(), 'abc')
})
