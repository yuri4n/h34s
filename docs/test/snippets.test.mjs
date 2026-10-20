// claude 2026-10-20: created: tests of the shown code's extraction (#52)
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

import {
  extractMarkedFence,
  extractRegion,
  joinRanges,
  lineDiff,
  SnippetError,
  zioBuild,
} from '../snippets/extract.mjs'
import {
  files,
  greeting,
  loadSnippets,
  readRepositoryFile,
  readSource,
  tree,
} from '../snippets/sources.mjs'

const lines = text => text.join('\n')

test('a region is the slice between its markers, minus nested markers', () => {
  const source = lines([
    'package a',
    '// #region outer',
    'val a = 1',
    '  // #region inner',
    '  val b = 2',
    '  // #endregion inner',
    '// #endregion outer',
  ])
  assert.equal(extractRegion(source, 'inner', 'A.scala'), 'val b = 2')
  assert.equal(
    extractRegion(source, 'outer', 'A.scala'),
    'val a = 1\n  val b = 2',
  )
})

test('a missing, open or repeated region names the file and region', () => {
  assert.throws(() => extractRegion('val a = 1', 'tour', 'A.scala'), (e) => {
    assert.ok(e instanceof SnippetError)
    assert.match(e.message, /^A\.scala: region "tour" not found/)
    return true
  })
  assert.throws(
    () => extractRegion('// #region tour\nval a = 1', 'tour', 'B.scala'),
    /B\.scala: region "tour" has no "#endregion tour" line/,
  )
  const twice = lines([
    '// #region t',
    'a',
    '// #endregion t',
    '// #region t',
    'b',
    '// #endregion t',
  ])
  assert.throws(
    () => extractRegion(twice, 't', 'C.scala'),
    /C\.scala: region "t" is marked more than once/,
  )
})

test('a marked fence gives its body without the fence indentation', () => {
  const markdown = lines([
    '1. Point it:',
    '',
    '   <!-- tour: h34s.config.mjs -->',
    '   ```js',
    '   export default {',
    '     a: 1,',
    '   }',
    '   ```',
  ])
  assert.equal(
    extractMarkedFence(markdown, 'h34s.config.mjs', 'guide.md'),
    'export default {\n  a: 1,\n}',
  )
  assert.throws(
    () => extractMarkedFence(markdown, 'build.sbt', 'guide.md'),
    /guide\.md: marker "<!-- tour: build.sbt -->" not found/,
  )
  assert.equal(
    extractMarkedFence('<!-- tour: x -->\n\n```js\na\n```', 'x', 'g.md'),
    'a',
  )
  assert.throws(
    () => extractMarkedFence(
      '<!-- tour: x -->\ntext\n```js\na\n```',
      'x',
      'g.md',
    ),
    /g\.md: marker .* is not followed by a code fence/,
  )
})

test('the line diff marks the lines outside the common subsequence', () => {
  assert.deepEqual(lineDiff('a\nb\nc', 'a\nB\nc'), { left: [2], right: [2] })
  assert.deepEqual(lineDiff('a\nb', 'a\nb\nc'), { left: [], right: [3] })
  assert.deepEqual(lineDiff('x\na', 'a'), { left: [1], right: [] })
  assert.deepEqual(lineDiff('same', 'same'), { left: [], right: [] })
})

test('two ranges are joined with a // ... line', () => {
  assert.equal(joinRanges(['a: [', '  b: 1']), 'a: [\n  // ...\n  b: 1')
})

test('the ZIO build follows the guide rule; Kyo names kyo-h3-ext once', () => {
  const kyo = readSource(files['build.sbt'].kyo, readRepositoryFile)
  assert.equal(kyo.split('kyo-h3-ext').length - 1, 1)
  const zio = zioBuild(kyo, 'guide')
  assert.equal(zio, kyo.replace('kyo-h3-ext', 'zio-h3-ext'))
  assert.ok(!zio.includes('kyo-h3-ext'))
  assert.throws(
    () => zioBuild('no module here', 'guide.md'),
    /guide\.md: the Kyo build\.sbt must name kyo-h3-ext exactly once/,
  )
})

test('the homepage handler is the exact slice of its source file', () => {
  const snippets = loadSnippets()
  const source = readRepositoryFile(greeting.kyo.file).split('\n')
  const start = source.indexOf('// #region greeting-handler')
  const end = source.indexOf('// #endregion greeting-handler')
  assert.ok(start >= 0 && end > start)
  assert.equal(
    snippets.greeting.kyo.text,
    source.slice(start + 1, end).join('\n'),
  )
  for (const runtime of ['kyo', 'zio']) {
    const { text } = snippets.greeting[runtime]
    assert.ok(text.startsWith('@JSExportTopLevel("default", "'), runtime)
    assert.ok(text.includes(`Route.post("/api/${runtime}/greeting")`), runtime)
  }
  // Same slots: only the export name, the service call and the route differ.
  assert.deepEqual(snippets.greeting.kyo.changed, [1, 6, 8])
  assert.deepEqual(snippets.greeting.zio.changed, [1, 6, 8])
})

test('no shown text holds a region, tour or annotation marker', () => {
  const marker = /#(?:end)?region|claude \d{4}-\d{2}-\d{2}:|<!-- tour:/
  for (const [id, file] of Object.entries(loadSnippets().files)) {
    for (const runtime of ['kyo', 'zio']) {
      const { text } = file[runtime]
      assert.ok(text.trim().length > 0, `${id} (${runtime}) is empty`)
      assert.ok(!marker.test(text), `${id} (${runtime}) shows a marker`)
    }
  }
})

test('every selectable tree entry has a file, and every file is listed', () => {
  const ids = tree.filter(entry => entry.id).map(entry => entry.id)
  assert.deepEqual([...ids].sort(), Object.keys(files).sort())
  assert.ok(tree.some(entry => entry.path === 'server/.scala/'
    && entry.generated))
})

test('the homepage and the tour hold no hand-typed code', async () => {
  const app = new URL('../app/', import.meta.url)
  const handTyped
    = /@JSExportTopLevel|syntax-(?:keyword|type|string)|Env\.use|defineConfig/
  for (const name of ['pages/index.vue']) {
    const source = await readFile(new URL(name, app), 'utf8')
    assert.ok(source.includes('virtual:h34s-snippets'), `${name} reads it`)
    assert.ok(!handTyped.test(source), `${name} holds hand-typed code`)
  }
})
