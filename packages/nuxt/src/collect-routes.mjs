// Runs in a child process of `h34s-scala`: imports every linked module of one output directory and
// sends the h34s record of each default export to the parent. Linked code runs here, never in
// the CLI's own process, so its module cache and globals stay apart.
import { readdirSync } from 'node:fs'
import { join } from 'node:path'
import process from 'node:process'
import { pathToFileURL } from 'node:url'

const key = Symbol.for('h34s.handler')

function send(message) {
  process.send(message, () => process.exit(0))
}

async function collect(directory) {
  const modules = []
  const files = readdirSync(directory, { withFileTypes: true })
    .filter(entry => entry.isFile() && entry.name.endsWith('.js'))
    .map(entry => entry.name)
    .sort()
  for (const file of files) {
    let linked
    try {
      linked = await import(pathToFileURL(join(directory, file)).href)
    }
    catch (error) {
      return { error: `${file}: ${error?.stack ?? error}` }
    }
    const handler = linked.default
    const record = typeof handler === 'function' ? handler[key] : undefined
    if (record === undefined || record === null)
      continue
    try {
      const types = typeof record.types === 'function' ? record.types() : undefined
      modules.push({
        file,
        version: record.version,
        route: record.route,
        method: record.method,
        success: types?.success,
        errors: types?.errors,
        named: types?.named, // claude 2026-10-20: changed: the success type with model names and the models (ADR-0014)
      })
    }
    catch (error) {
      return { error: `${file}: ${error?.stack ?? error}` }
    }
  }
  return { modules }
}

collect(process.argv[2]).then(send, error => send({ error: String(error?.stack ?? error) }))
