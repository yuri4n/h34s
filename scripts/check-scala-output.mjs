import assert from 'node:assert/strict'
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const mode = process.argv[2]
assert.ok(['development', 'production'].includes(mode), 'Pass development or production')

const target = 'scala-modules/svr/target'
const outputName = mode === 'production' ? 'nuxtsvr-opt' : 'nuxtsvr-fastopt'
const candidates = readdirSync(target)
  .filter(name => name.startsWith('scala-'))
  .map(name => join(target, name, outputName))
  .filter(existsSync)
assert.equal(candidates.length, 1, `Expected one ${outputName} directory; remove obsolete Scala target directories`)
const source = candidates[0]
const destination = 'server/.scala'

function files(directory, prefix = '') {
  return readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const relative = join(prefix, entry.name)
    return entry.isDirectory() ? files(join(directory, entry.name), relative) : [relative]
  }).sort()
}

const expected = files(source)
assert.ok(expected.includes('test.js'), 'The existing /api/test export must be linked')
assert.deepEqual(files(destination), expected, 'Staged files differ from linker output (missing or stale files)')
for (const file of expected) {
  assert.deepEqual(readFileSync(join(destination, file)), readFileSync(join(source, file)), `Stale contents: ${file}`)
}
console.log(`Verified ${expected.length} staged files against ${mode} linker output`)
