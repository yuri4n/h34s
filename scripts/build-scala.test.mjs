import assert from 'node:assert/strict'
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
// Build checks use Node's built-in runner without a test-framework dependency.
// eslint-disable-next-line test/no-import-node-test
import { test } from 'node:test'
import { buildScala } from './build-scala.mjs'

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'h34s-production-link-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  for (const name of ['.scala', '.scala-kyo']) {
    mkdirSync(join(root, 'server', name), { recursive: true })
    writeFileSync(
      join(root, 'server', name, 'test.js'),
      'stale development code',
    )
    writeFileSync(join(root, 'server', name, 'stale-chunk.js'), 'old chunk')
  }
  return root
}

test('production linking clears both development outputs before linking the two route modules', (
  t,
) => {
  const root = fixture(t)
  buildScala({ root, run: (_command, tasks, options) => {
    assert.equal(options.cwd, root)
    assert.deepEqual(tasks, ['nuxtsvr/fullLinkJS', 'kyoExample/fullLinkJS'])
    for (const name of ['.scala', '.scala-kyo']) {
      assert.equal(existsSync(join(root, 'server', name)), false)
      mkdirSync(join(root, 'server', name), { recursive: true })
      const files = name === '.scala'
        ? ['test.js', 'notes-get.js', 'notes-post.js']
        : ['kyo-greeting-get.js', 'kyo-greeting-post.js']
      for (const filename of files)
        writeFileSync(join(root, 'server', name, filename), 'fresh production code')
    }
    return { status: 0 }
  } })
  assert.equal(existsSync(join(root, 'server/.scala/stale-chunk.js')), false)
})

test('failed linking cannot leave stale route output available to Nitro', (
  t,
) => {
  const root = fixture(t)
  assert.throws(
    () => buildScala({ root, run: () => ({ status: 1 }) }),
    /exit 1/,
  )
  assert.equal(existsSync(join(root, 'server/.scala/test.js')), false)
  assert.equal(existsSync(join(root, 'server/.scala-kyo/test.js')), false)
})

test('a successful process with missing or empty route artifacts still fails the build', (
  t,
) => {
  const root = fixture(t)
  assert.throws(
    () => buildScala({ root, run: () => ({ status: 0 }) }),
    /did not produce server\/\.scala\/test.js/,
  )
  assert.throws(() => buildScala({ root, run: () => {
    mkdirSync(join(root, 'server/.scala'), { recursive: true })
    writeFileSync(join(root, 'server/.scala/test.js'), '')
    return { status: 0 }
  } }), /did not produce/)
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
