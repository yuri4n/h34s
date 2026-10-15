import { spawnSync } from 'node:child_process'
import process from 'node:process'

// Docus 5.13.0 ships source with these known upstream diagnostics. Keep the
// exception tied to exact files, positions, and codes. New dependency or
// application diagnostics fail, as do interrupted or crashed checks.
const known = new Map([
  ['app/components/OgImage/Docs.takumi.vue(19,84):2731', 1],
  ['app/components/OgImage/Landing.takumi.vue(62,73):2731', 1],
  ['app/composables/useSeo.ts(37,53):2339', 1],
  ['app/composables/useSeo.ts(139,41):2339', 1],
  ['app/composables/useSeo.ts(172,5):2322', 1],
  ['app/composables/useSeo.ts(221,5):2322', 1],
  ['modules/assistant/index.ts(171,16):2664', 2],
  ['modules/skills/index.ts(154,16):2664', 2],
])

const ERROR_LINE = /^(\S[^(]*)\((\d+,\d+)\): error TS(\d+)/

const result = spawnSync('nuxt', ['typecheck'], { encoding: 'utf8' })

if (result.error || result.signal) {
  console.error('typecheck: process failed.', result.error || result.signal)
  process.exit(1)
}

const output = `${result.stdout}${result.stderr}`
process.stdout.write(output)

const diagnostics = output
  .split('\n')
  .map(line => ERROR_LINE.exec(line))
  .filter(match => match !== null)
const unexpected = diagnostics.filter(([, file, position, code]) => {
  const upstream = /node_modules\/docus\/(.+)$/.exec(file)
  if (!upstream || !file.includes('docus@5.13.0_'))
    return true
  const key = `${upstream[1]}(${position}):${code}`
  const remaining = known.get(key) ?? 0
  known.set(key, remaining - 1)
  return remaining <= 0
})

if (unexpected.length > 0) {
  console.error(`typecheck: ${unexpected.length} unexpected diagnostic(s).`)
  process.exit(1)
}

const fatal = /FATAL ERROR|ERR_[A-Z_]+|^\s*ERROR\b/m.test(output)
  || output.split('\n').some(line => /error TS\d+/.test(line)
    && !ERROR_LINE.test(line))
const expectedFailure = result.status === 2 && diagnostics.length > 0
if (fatal || (result.status !== 0 && !expectedFailure)) {
  console.error(`typecheck: nuxt typecheck failed (exit ${result.status}).`)
  process.exit(result.status || 1)
}

if (diagnostics.length > 0)
  console.log(`typecheck: ${diagnostics.length} known Docus diagnostics.`)
console.log('typecheck: no application, shared-layer, or new upstream errors.')
