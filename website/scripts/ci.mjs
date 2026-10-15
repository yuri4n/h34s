import { spawn } from 'node:child_process'
import process from 'node:process'
import { stripVTControlCharacters } from 'node:util'

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
    const child = spawn('pnpm', ['run', stage], {
      stdio: ['inherit', 'pipe', 'pipe'],
    })
    let tail = ''
    for (const [source, destination] of [
      [child.stdout, process.stdout],
      [child.stderr, process.stderr],
    ]) {
      source.setEncoding('utf8')
      source.on('data', (chunk) => {
        tail = (tail + stripVTControlCharacters(chunk)).slice(-3000)
      })
      source.pipe(destination, { end: false })
    }
    let spawnError
    child.once('error', (error) => {
      spawnError = error
    })
    child.once('close', (status, signal) => {
      resolve({ status, signal, error: spawnError, tail })
    })
  })
}

for (const stage of ['check', 'build']) {
  await report('notice', `${stage} started`)
  const result = await runStage(stage)
  if (result.error || result.signal || result.status !== 0) {
    const message = `Documentation ${stage} failed: `
      + `${result.error || result.signal || `exit ${result.status}`}`
    const details = result.tail ? `${message}\n${result.tail}` : message
    await report('error', details)
    // Error slots can already be exhausted by a dependency's annotations.
    await report('notice', details)
    process.exitCode = result.status > 0 ? result.status : 1
    break
  }
  await report('notice', `${stage} passed`)
}
