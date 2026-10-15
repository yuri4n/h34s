import { spawnSync } from 'node:child_process'
import process from 'node:process'
import { stripVTControlCharacters } from 'node:util'

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

function fail(message, status = 1) {
  console.error(`typecheck: ${message}`)
  if (process.env.GITHUB_ACTIONS === 'true') {
    console.error(
      `::error title=Documentation typecheck::${message.replaceAll(
        '%',
        '%25',
      ).replaceAll(
        '\r',
        '%0D',
      ).replaceAll(
        '\n',
        '%0A',
      )}`,
    )
  }
  process.exit(status)
}

const result = spawnSync('nuxt', ['typecheck'], { encoding: 'utf8' })

if (result.error || result.signal)
  fail(`process failed: ${result.error || result.signal}`)

const rawOutput = `${result.stdout}${result.stderr}`
process.stdout.write(rawOutput)
const output = stripVTControlCharacters(rawOutput)

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
  fail(
    `${unexpected.length} unexpected diagnostic(s): ${unexpected.map(
      match => match[0],
    ).join(
      '; ',
    )}`,
  )
}

const fatal = /FATAL ERROR|ERR_[A-Z_]+|^\s*ERROR\b/m.test(output)
  || output.split('\n').some(line => /error TS\d+/.test(line)
    && !ERROR_LINE.test(line))
// TypeScript build mode uses 1 when no referenced project succeeds and 2
// when some do. Both represent diagnostics; the allowlist above must pass.
const expectedFailure = [1, 2].includes(result.status)
  && diagnostics.length > 0
if (fatal || (result.status !== 0 && !expectedFailure)) {
  fail(
    `nuxt typecheck failed (exit ${result.status}; `
    + `parsed diagnostics ${diagnostics.length}; fatal output ${fatal}).`,
    result.status || 1,
  )
}

if (diagnostics.length > 0)
  console.log(`typecheck: ${diagnostics.length} known Docus diagnostics.`)
console.log('typecheck: no application, shared-layer, or new upstream errors.')
