// claude 2026-10-20: created: the CLI in layout C, with a fake sbt on PATH (ADR-0009)
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { delimiter, join } from 'node:path'
import process from 'node:process'
// The package uses Node's runner without additional test dependencies.
// eslint-disable-next-line test/no-import-node-test
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const cli = fileURLToPath(new URL('../src/cli.mjs', import.meta.url))

/**
 * A fake `sbt` that records its working directory and arguments, and links one h34s route module
 * into H34S_TEST_OUTPUT, as a linker configured with that output directory would.
 */
const fakeSbt = `#!/usr/bin/env node
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
const output = process.env.H34S_TEST_OUTPUT
mkdirSync(output, { recursive: true })
writeFileSync(join(output, 'hello-get.js'), \`const handler = () => 'ok'
Object.defineProperty(handler, Symbol.for('h34s.handler'), { value: { version: 1, route: '/api/hello', method: 'get', types: () => ({ success: 'string', errors: 'unknown' }) } })
export default handler
\`)
writeFileSync(process.env.H34S_TEST_RECORD, JSON.stringify({ cwd: process.cwd(), args: process.argv.slice(2) }))
`

test('layout C: `h34s-scala dev` in the Nuxt root runs sbt in the sibling build and writes the routes under the Nuxt root', (t) => {
  // The real path: sbt and the CLI see their working directory without symlinks (macOS /var).
  const repository = realpathSync(mkdtempSync(join(tmpdir(), 'h34s-cli-layout-c-')))
  t.after(() => rmSync(repository, { recursive: true, force: true }))
  const web = join(repository, 'web')
  const scala = join(repository, 'scala-server')
  const bin = join(repository, 'bin')
  for (const directory of [web, scala, bin])
    mkdirSync(directory)
  writeFileSync(join(scala, 'build.sbt'), '')
  writeFileSync(join(web, 'h34s.config.mjs'), `export default { sbtRoot: '../scala-server', modules: [{ project: 'server', output: 'server/.scala' }] }\n`)
  writeFileSync(join(bin, 'sbt'), fakeSbt)
  chmodSync(join(bin, 'sbt'), 0o755)
  const record = join(repository, 'sbt.json')
  const printed = execFileSync(process.execPath, [cli, 'dev'], {
    cwd: web,
    encoding: 'utf8',
    env: {
      ...process.env,
      PATH: `${bin}${delimiter}${process.env.PATH}`,
      H34S_TEST_OUTPUT: join(web, 'server/.scala'),
      H34S_TEST_RECORD: record,
    },
  })
  assert.deepEqual(JSON.parse(readFileSync(record, 'utf8')), {
    cwd: scala,
    args: ['-Dsbt.supershell=false', '-batch', 'server/fastLinkJS'],
  })
  assert.match(printed, /\[h34s\] 1 route and 0 models in server\/\.scala/)
  assert.deepEqual(
    JSON.parse(readFileSync(join(web, 'server/.scala/h34s-routes.json'), 'utf8')).routes,
    [{ module: 'hello-get.js', route: '/api/hello', method: 'get' }],
  )
  assert.ok(existsSync(join(web, 'server/.scala/hello-get.d.ts')))
  assert.equal(existsSync(join(scala, 'server')), false, 'nothing is written into the sbt build')
})

test('the CLI explains a config whose sbtRoot has no sbt build, and does not start sbt', (t) => {
  const repository = realpathSync(mkdtempSync(join(tmpdir(), 'h34s-cli-no-build-')))
  t.after(() => rmSync(repository, { recursive: true, force: true }))
  const web = join(repository, 'web')
  const bin = join(repository, 'bin')
  mkdirSync(web)
  mkdirSync(bin)
  writeFileSync(join(web, 'h34s.config.mjs'), `export default { modules: [{ output: 'server/.scala' }] }\n`)
  writeFileSync(join(bin, 'sbt'), fakeSbt)
  chmodSync(join(bin, 'sbt'), 0o755)
  const record = join(repository, 'sbt.json')
  let failure
  try {
    execFileSync(process.execPath, [cli, 'build'], {
      cwd: web,
      encoding: 'utf8',
      stdio: 'pipe',
      env: { ...process.env, PATH: `${bin}${delimiter}${process.env.PATH}`, H34S_TEST_OUTPUT: join(web, 'server/.scala'), H34S_TEST_RECORD: record },
    })
  }
  catch (error) {
    failure = error
  }
  assert.equal(failure?.status, 1)
  assert.equal(
    failure.stderr.trim(),
    `[h34s] sbtRoot is not set, so the sbt build is the config file's directory, ${web}, which has no build.sbt and no project/ directory. Set sbtRoot in ${join(web, 'h34s.config.mjs')} to the directory of your sbt build, relative to that file, for example '..'.`,
  )
  assert.equal(existsSync(record), false, 'sbt did not start')
})
