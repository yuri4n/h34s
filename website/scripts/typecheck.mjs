import { spawnSync } from 'node:child_process'
import process from 'node:process'

// `nuxt typecheck` for a Docus site.
//
// Docus 5.13.0 ships its source (.ts and .vue files), so vue-tsc also checks
// the Docus files, and it finds 8 errors in them (node_modules/docus: OgImage
// components, useSeo.ts, two module augmentations). They are not ours to fix.
// This script prints the full output and fails only when an error is in a
// file outside node_modules (this site and the library layers).
// Copy it to a new docs site with the `typecheck` script of package.json.

const ERROR_LINE = /^(\S[^(]*)\(\d+,\d+\): error TS\d+/

const result = spawnSync('nuxt', ['typecheck'], { encoding: 'utf8' })

if (result.error) {
  console.error(`typecheck: cannot run nuxt: ${result.error.message}`)
  process.exit(1)
}

const output = `${result.stdout}${result.stderr}`
process.stdout.write(output)

const files = output
  .split('\n')
  .map(line => ERROR_LINE.exec(line))
  .filter(match => match !== null)
  .map(match => match[1])
const ours = files.filter(file => !file.includes('node_modules/'))
const theirs = files.length - ours.length

if (ours.length > 0) {
  console.error(`typecheck: ${ours.length} error(s) outside node_modules.`)
  process.exit(1)
}

if (result.status !== 0 && files.length === 0) {
  console.error(`typecheck: nuxt typecheck failed (exit ${result.status}).`)
  process.exit(result.status ?? 1)
}

if (theirs > 0)
  console.log(`typecheck: ignored ${theirs} error line(s) in node_modules.`)
console.log('typecheck: no errors outside node_modules.')
