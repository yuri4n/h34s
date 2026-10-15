import { spawn } from 'node:child_process'
import process from 'node:process'

async function report(level, message) {
  const stream = level === 'error' ? process.stderr : process.stdout
  const escaped = message
    .replaceAll('%', '%25')
    .replaceAll('\r', '%0D')
    .replaceAll('\n', '%0A')
  const line = process.env.GITHUB_ACTIONS === 'true'
    ? `::${level} title=Documentation CI::${escaped}`
    : message

  // Wait for the write instead of exiting while an annotation is buffered.
  await new Promise((resolve, reject) => {
    stream.write(`${line}\n`, error => error ? reject(error) : resolve())
  })
}

function runStage(stage) {
  return new Promise((resolve) => {
    const child = spawn('pnpm', ['run', stage], { stdio: 'inherit' })
    let spawnError
    child.once('error', (error) => {
      spawnError = error
    })
    child.once('close', (status, signal) => {
      resolve({ status, signal, error: spawnError })
    })
  })
}

for (const stage of ['check', 'build']) {
  await report('notice', `${stage} started`)
  const result = await runStage(stage)
  if (result.error || result.signal || result.status !== 0) {
    const message = `Documentation ${stage} failed: `
      + `${result.error || result.signal || `exit ${result.status}`}`
    await report('error', message)
    process.exitCode = result.status > 0 ? result.status : 1
    break
  }
  await report('notice', `${stage} passed`)
}
