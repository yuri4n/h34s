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

function buildEnvironment() {
  const env = { ...process.env }
  const options = env.NODE_OPTIONS || ''
  const explicitHeap = /(?:^|[\s"])--max[-_]old[-_]space[-_]size(?:[=\s"]|$)/
  // Prerendering exceeds the hosted runner's default 2 GiB heap.
  if (!explicitHeap.test(options)) {
    env.NODE_OPTIONS = `${options} --max-old-space-size=4096`.trim()
  }
  return env
}

function runStage(stage) {
  return new Promise((resolve) => {
    const child = spawn('pnpm', ['run', stage], {
      stdio: ['inherit', 'pipe', 'pipe'],
      env: stage === 'build' ? buildEnvironment() : process.env,
    })
    let tail = ''
    for (const [source, destination] of [
      [child.stdout, process.stdout],
      [child.stderr, process.stderr],
    ]) {
      source.setEncoding('utf8')
      source.on('data', (chunk) => {
        tail = (tail + stripVTControlCharacters(chunk)).slice(-12000)
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
    const details = result.tail
      ? `${message}\n${result.tail.slice(-3000)}`
      : message
    await report('error', details)
    // Keep the error header when long stack traces fill the end of the log.
    // Four chunks plus the stage notices stay below GitHub's ten-notice cap.
    const chunks = result.tail.match(/[\s\S]{1,3000}/g) ?? ['']
    for (const [index, chunk] of chunks.entries()) {
      await report(
        'notice',
        `${message} (output ${index + 1}/${chunks.length})\n${chunk}`,
      )
    }
    process.exitCode = result.status > 0 ? result.status : 1
    break
  }
  await report('notice', `${stage} passed`)
}
