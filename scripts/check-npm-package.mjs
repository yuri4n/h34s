import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import {
  chmodSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const temporary = mkdtempSync(join(tmpdir(), 'h34s-packed-consumer-'))
const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'
const args = process.argv.slice(2)
assert.ok(
  args.length === 0 || (args.length === 2 && args[0] === '--tarball'),
  'Usage: check-npm-package.mjs [--tarball <path>]',
)

function run(command, args, options = {}) {
  return execFileSync(command, args, { encoding: 'utf8', ...options })
}

try {
  let tarball = args[1] && resolve(args[1])
  if (!tarball) {
    const [packed] = JSON.parse(
      run(
        npm,
        [
          'pack',
          './packages/nuxt',
          '--ignore-scripts',
          '--json',
          '--cache',
          join(temporary, 'pack-cache'),
          '--pack-destination',
          temporary,
        ],
        { cwd: root },
      ),
    )
    tarball = join(temporary, packed.filename)
  }
  const files = run('tar', ['-tzf', tarball]).trim().split('\n')
  assert.ok(files.includes('package/README.md'), 'README is shipped')
  for (const file of files) {
    assert.match(
      file,
      /^package\/(?:package\.json|README\.md|src\/(?:[\w-]+\/)*[\w-]+\.(?:mjs|d\.mts))$/,
      `Unexpected packed file: ${file}`,
    )
  }
  const app = join(temporary, 'independent app')
  mkdirSync(app)
  writeFileSync(
    join(app, 'package.json'),
    '{"name":"packed-consumer","private":true,"type":"module"}\n',
  )
  // A fresh npm cache, no workspace symlinks, no network and no install scripts.
  run(
    npm,
    [
      'install',
      '--offline',
      '--ignore-scripts',
      '--omit=optional',
      '--legacy-peer-deps',
      '--no-audit',
      '--no-fund',
      '--cache',
      join(temporary, 'npm-cache'),
      tarball,
    ],
    { cwd: app },
  )
  const installed = join(app, 'node_modules/@yuri4n/h34s-nuxt')
  const pkg = JSON.parse(readFileSync(join(installed, 'package.json'), 'utf8'))
  assert.equal(pkg.name, '@yuri4n/h34s-nuxt')
  assert.equal(pkg.publishConfig.registry, 'https://npm.pkg.github.com')
  assert.equal(pkg.repository.directory, 'packages/nuxt')
  assert.equal(Object.keys(pkg.dependencies ?? {}).length, 0)
  for (const entry of Object.values(pkg.exports)) {
    for (const target of typeof entry === 'string'
      ? [entry]
      : Object.values(entry)) {
      assert.ok(
        readFileSync(join(installed, target)).length > 0,
        `Missing export: ${target}`,
      )
    }
  }
  const cli = join(app, 'node_modules/.bin/h34s-scala')
  assert.match(run(cli, ['--help'], { cwd: app }), /Usage: h34s-scala/)
  writeFileSync(
    join(app, 'h34s.config.mjs'),
    `import { defineConfig } from '@yuri4n/h34s-nuxt/build'
export default defineConfig({ modules: [{ output: 'server/.scala', entries: ['hello.js'] }] })
`,
  )
  const bin = join(temporary, 'bin')
  mkdirSync(bin)
  const sbt = join(bin, 'sbt')
  writeFileSync(
    sbt,
    `#!/usr/bin/env node
import { mkdirSync, writeFileSync } from 'node:fs'
mkdirSync('server/.scala', { recursive: true })
writeFileSync('server/.scala/hello.js', 'export const hello = 42')
writeFileSync('sbt-args.json', JSON.stringify(process.argv.slice(2)))
`,
  )
  chmodSync(sbt, 0o755)
  writeFileSync(join(temporary, 'package.json'), '{"type":"module"}')
  const env = { ...process.env, PATH: `${bin}:${process.env.PATH}` }
  run(cli, ['build', '--root', app], { cwd: temporary, env })
  assert.deepEqual(
    JSON.parse(readFileSync(join(app, 'sbt-args.json'), 'utf8')),
    ['-Dsbt.supershell=false', '-batch', 'fullLinkJS'],
  )
  assert.throws(() => run(cli, ['unknown'], { cwd: app, env, stdio: 'pipe' }))
  assert.throws(() =>
    run(cli, ['dev', '--root'], { cwd: app, env, stdio: 'pipe' }),
  )
  writeFileSync(
    join(app, 'probe.mjs'),
    `import assert from 'node:assert/strict'
import h34sNuxt from '@yuri4n/h34s-nuxt'
import { loadConfig } from '@yuri4n/h34s-nuxt/build'
import { scalaDevModules, scalaSourceMaps } from '@yuri4n/h34s-nuxt/rollup'
assert.equal((await loadConfig()).modules.length, 1)
assert.equal(typeof scalaSourceMaps, 'function')
const resolved = await scalaDevModules(process.cwd()).resolveId.handler(process.cwd() + '/server/.scala/hello.js')
assert.equal((await import(resolved.id)).hello, 42)
let configure
await h34sNuxt({}, { options: { rootDir: process.cwd() }, hook(name, fn) { assert.equal(name, 'nitro:config'); configure = fn } })
const dev = { dev: true }
configure(dev)
assert.equal(dev.sourceMap, true)
assert.equal(dev.devServer.watch.length, 1)
assert.equal(dev.rollupConfig.plugins.length, 2)
assert.ok(dev.plugins[0].includes('node_modules/@yuri4n/h34s-nuxt/'))
assert.ok(dev.externals.inline[0].includes('node_modules/@yuri4n/h34s-nuxt/'))
`,
  )
  run(process.execPath, ['probe.mjs'], { cwd: app })
  const integrity = createHash('sha512')
    .update(readFileSync(tarball))
    .digest('base64')
  console.log(
    `Verified ${pkg.name}@${pkg.version}: ${files.length} packed files, isolated install, exports, CLI and Nuxt hooks; sha512-${integrity}`,
  )
}
finally {
  rmSync(temporary, { recursive: true, force: true })
}
