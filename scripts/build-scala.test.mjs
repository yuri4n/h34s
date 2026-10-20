import assert from 'node:assert/strict'
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
// Build checks use Node's built-in runner without a test-framework dependency.
// eslint-disable-next-line test/no-import-node-test
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { loadConfig, buildScala as runBuild, writeRoutes } from '@yuri4n/h34s-nuxt/build'
import example from '../examples/nuxt/h34s.config.mjs' // claude 2026-10-20: changed: the example's own config, not a second one (ADR-0009)

// claude 2026-10-20: changed: the helpers run in examples/nuxt with the example's sbtRoot (ADR-0009)
const repository = resolve(dirname(fileURLToPath(import.meta.url)), '..')
/** The example's config as `loadConfig` reads it from `<root>/examples/nuxt`. */
const configIn = root => ({ ...example, sbtRoot: resolve(root, 'examples/nuxt', example.sbtRoot) })
const buildScala = ({ root, ...options }) => runBuild({ ...options, root: join(root, 'examples/nuxt'), config: configIn(root) })
const routesOf = root => writeRoutes({ root: join(root, 'examples/nuxt'), config: configIn(root) })

// claude 2026-10-20: created: the example's sbtRoot is the repository build (ADR-0009)
test('the example\'s config runs sbt in the repository build', async () => {
  assert.equal((await loadConfig({ root: join(repository, 'examples/nuxt') })).sbtRoot, repository)
})

/** A linked route module: its default export carries the record that `EventHandler.at` writes. */
function routeModule(route) {
  return `const handler = () => 'ok'
Object.defineProperty(handler, Symbol.for('h34s.handler'), {
  value: { version: 1, route: ${JSON.stringify(route)}, types: () => ({ success: 'string', errors: 'unknown' }) },
})
export default handler
`
}

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'h34s-production-link-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  writeFileSync(join(root, 'build.sbt'), '') // claude 2026-10-20: created: the repository root is the sbt build (ADR-0009)
  for (const name of ['.scala', '.scala-kyo']) {
    mkdirSync(join(root, 'examples/nuxt/server', name), { recursive: true })
    writeFileSync(
      join(root, 'examples/nuxt/server', name, 'test.js'),
      'stale development code',
    )
    writeFileSync(join(root, 'examples/nuxt/server', name, 'stale-chunk.js'), 'old chunk')
  }
  return root
}

test('production linking clears both development outputs before linking the two route modules', (
  t,
) => {
  const root = fixture(t)
  buildScala({ root, run: (_command, tasks, options) => {
    assert.equal(options.cwd, root)
    assert.deepEqual(
      tasks,
      [
        '-Dsbt.supershell=false',
        '-batch',
        'nuxtsvr/fullLinkJS',
        'kyoExample/fullLinkJS',
      ],
    )
    for (const name of ['.scala', '.scala-kyo']) {
      assert.equal(existsSync(join(root, 'examples/nuxt/server', name)), false)
      mkdirSync(join(root, 'examples/nuxt/server', name), { recursive: true })
      // claude 2026-10-20: changed: the ZIO greeting module (issue #52)
      const files = name === '.scala'
        ? ['test.js', 'notes-get.js', 'notes-post.js', 'zio-greeting-post.js']
        : ['kyo-greeting-get.js', 'kyo-greeting-post.js']
      for (const filename of files) {
        writeFileSync(
          join(root, 'examples/nuxt/server', name, filename),
          'fresh production code',
        )
      }
    }
    return { status: 0 }
  } })
  assert.equal(existsSync(join(root, 'examples/nuxt/server/.scala/stale-chunk.js')), false)
})

test('failed linking cannot leave stale route output available to Nitro', (
  t,
) => {
  const root = fixture(t)
  assert.throws(
    () => buildScala({ root, run: () => ({ status: 1 }) }),
    /exit 1/,
  )
  assert.equal(existsSync(join(root, 'examples/nuxt/server/.scala/test.js')), false)
  assert.equal(existsSync(join(root, 'examples/nuxt/server/.scala-kyo/test.js')), false)
})

test('a successful process without linked route modules still fails the build', async (t) => {
  const root = fixture(t)
  buildScala({ root, run: () => ({ status: 0 }) })
  await assert.rejects(routesOf(root), /Scala\.js output server\/\.scala has no h34s handlers/) // claude 2026-10-20: changed: outputs are relative to the example (ADR-0009)
  buildScala({ root, run: () => {
    for (const name of ['.scala', '.scala-kyo']) {
      mkdirSync(join(root, 'examples/nuxt/server', name), { recursive: true })
      writeFileSync(join(root, 'examples/nuxt/server', name, 'test.js'), '')
    }
    return { status: 0 }
  } })
  await assert.rejects(routesOf(root), /has no h34s handlers/)
})

test('the route manifests list the linked routes of both outputs', async (t) => {
  const root = fixture(t)
  buildScala({ root, run: () => {
    for (const [name, route] of [['.scala', '/api/test'], ['.scala-kyo', '/api/kyo/greeting']]) {
      mkdirSync(join(root, 'examples/nuxt/server', name), { recursive: true })
      writeFileSync(join(root, 'examples/nuxt/server', name, 'route.js'), routeModule(route))
    }
    return { status: 0 }
  } })
  const outputs = await routesOf(root)
  assert.deepEqual(outputs.map(output => output.routes), [
    [{ module: 'route.js', route: '/api/test' }],
    [{ module: 'route.js', route: '/api/kyo/greeting' }],
  ])
  assert.ok(existsSync(join(root, 'examples/nuxt/server/.scala-kyo/route.d.ts')))
})

test('process start errors and signals fail production linking', (t) => {
  const root = fixture(t)
  assert.throws(
    () => buildScala({
      root,
      run: () => ({ error: new Error('sbt unavailable') }),
    }),
    /sbt unavailable/,
  )
  assert.throws(
    () => buildScala({
      root,
      run: () => ({ status: null, signal: 'SIGTERM' }),
    }),
    /SIGTERM/,
  )
})
