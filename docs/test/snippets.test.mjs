// claude 2026-10-20: created: tests of the shown code's extraction (#52)
import assert from 'node:assert/strict'
import { readdir, readFile } from 'node:fs/promises'
import { join, relative } from 'node:path'
import test from 'node:test'

import ts from 'typescript'

import {
  extractMarkedFence,
  extractRegion,
  lineDiff,
  regionNames,
  SnippetError,
  tourMarkers,
  zioBuild,
} from '../snippets/extract.mjs'
import {
  allSources,
  files,
  greeting,
  loadSnippets,
  readRepositoryFile,
  readSource,
  repositoryRoot,
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

// claude 2026-10-20: created: annotations never reach the site (#52)
test('a region drops whole-line change annotations', () => {
  const source = lines([
    '// #region r',
    '// claude 2026-10-20: changed: the value (#52)',
    'val a = 1',
    '  # claude 2026-10-20: created: a key (#52)',
    '  b: 2',
    'val c = "claude 2026-10-20: created: kept, not a whole line"',
    '// #endregion r',
  ])
  assert.equal(
    extractRegion(source, 'r', 'A.scala'),
    [
      'val a = 1',
      '  b: 2',
      'val c = "claude 2026-10-20: created: kept, not a whole line"',
    ].join('\n'),
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

// claude 2026-10-20: created: a marker must stand right above its fence (#52)
test('a tour marker followed by a heading or a list is rejected', () => {
  for (const next of ['## Heading', '- item', '1. step', '<!-- other -->']) {
    assert.throws(
      () => extractMarkedFence(
        `<!-- tour: x -->\n\n${next}\n\n\`\`\`js\na\n\`\`\``,
        'x',
        'g.md',
      ),
      /g\.md: marker .* is not followed by a code fence/,
      next,
    )
  }
})

test('the line diff marks the lines outside the common subsequence', () => {
  assert.deepEqual(lineDiff('a\nb\nc', 'a\nB\nc'), { left: [2], right: [2] })
  assert.deepEqual(lineDiff('a\nb', 'a\nb\nc'), { left: [], right: [3] })
  assert.deepEqual(lineDiff('x\na', 'a'), { left: [1], right: [] })
  assert.deepEqual(lineDiff('same', 'same'), { left: [], right: [] })
})

// claude 2026-10-20: created: an elision becomes one indented line (#52)
test('an elision becomes one // ... line with the #elide indent', () => {
  const source = lines([
    '// #region r',
    'a: [',
    '  b,',
    '  // #elide',
    '  c,',
    '    d,',
    '  // #end-elide',
    '],',
    '# #elide',
    'e: 1',
    '# #end-elide',
    '// #endregion r',
  ])
  assert.equal(
    extractRegion(source, 'r', 'a.ts'),
    lines(['a: [', '  b,', '  // ...', '],', '// ...']),
  )
})

// claude 2026-10-20: created: an unbalanced elision stops the build (#52)
test('an open, unopened or nested elision names the file and region', () => {
  const region = body => lines(['// #region r', ...body, '// #endregion r'])
  assert.throws(
    () => extractRegion(region(['a', '// #elide', 'b']), 'r', 'a.ts'),
    (e) => {
      assert.ok(e instanceof SnippetError)
      assert.match(e.message, /^a\.ts: region "r" has an "#elide" line/)
      return true
    },
  )
  assert.throws(
    () => extractRegion(region(['a', '// #end-elide']), 'r', 'b.ts'),
    /b\.ts: region "r" has an "#end-elide" line without "#elide"/,
  )
  assert.throws(
    () => extractRegion(
      region(['// #elide', '// #elide', '// #end-elide', '// #end-elide']),
      'r',
      'c.ts',
    ),
    /c\.ts: region "r" has a nested "#elide" line/,
  )
})

// claude 2026-10-20: created: syntax errors of a TypeScript or JS text (#52)
/** Gives the parse errors of a text, read as TypeScript. */
function syntaxErrors(text, name) {
  const source = ts.createSourceFile(
    name,
    text,
    ts.ScriptTarget.Latest,
    false,
    ts.ScriptKind.TS,
  )
  return source.parseDiagnostics.map(diagnostic =>
    ts.flattenDiagnosticMessageText(diagnostic.messageText, ' '))
}

// claude 2026-10-20: created: the shown nuxt.config.ts is balanced (#52)
test('every shown TypeScript and JavaScript text parses', () => {
  const shown = loadSnippets().files
  for (const [id, file] of Object.entries(shown)) {
    if (file.lang !== 'ts' && file.lang !== 'js')
      continue
    for (const runtime of ['kyo', 'zio']) {
      assert.deepEqual(
        syntaxErrors(file[runtime].text, id),
        [],
        `${id} (${runtime}) does not parse`,
      )
    }
  }
  assert.ok(shown['nuxt.config.ts'].kyo.text
    .startsWith('export default defineNuxtConfig({'))
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

// claude 2026-10-20: changed: elision markers count as markers (#52)
test('no shown text holds a region, tour or annotation marker', () => {
  const marker
    = /#(?:end)?region|#(?:end-)?elide|claude \d{4}-\d{2}-\d{2}:|<!-- tour:/
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
  for (const name of [
    'pages/index.vue',
    'components/content/ProjectTour.vue',
  ]) {
    const source = await readFile(new URL(name, app), 'utf8')
    assert.ok(source.includes('virtual:h34s-snippets'), `${name} reads it`)
    assert.ok(!handTyped.test(source), `${name} holds hand-typed code`)
  }
})

// claude 2026-10-20: created: no marker without a reader (#52)
/** Every file below `dir`, as repository paths, without build output. */
async function walk(dir) {
  const skip = new Set([
    'node_modules',
    'target',
    '.nuxt',
    '.output',
    '.data',
    '.scala',
    'vendor',
  ])
  const found = []
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    if (skip.has(entry.name))
      continue
    const path = join(dir, entry.name)
    if (entry.isDirectory())
      found.push(...await walk(path))
    else
      found.push(relative(repositoryRoot, path))
  }
  return found
}

test('every region and tour marker is read by sources.mjs', async () => {
  const used = new Set(allSources().map(item =>
    `${item.kind} ${item.file} ${item.name}`))
  const orphans = []
  for (const file of await walk(join(repositoryRoot, 'examples/nuxt'))) {
    if (!/\.(?:scala|ts|mjs|js|vue)$/.test(file))
      continue
    for (const name of regionNames(readRepositoryFile(file))) {
      if (!used.has(`region ${file} ${name}`))
        orphans.push(`${file}: #region ${name}`)
    }
  }
  for (const file of await walk(join(repositoryRoot, 'docs/content'))) {
    if (!file.endsWith('.md'))
      continue
    for (const name of tourMarkers(readRepositoryFile(file))) {
      if (!used.has(`guide ${file} ${name}`))
        orphans.push(`${file}: <!-- tour: ${name} -->`)
    }
  }
  assert.deepEqual(orphans, [], 'markers that no source in sources.mjs reads')
})

test('the orphan check finds region and tour marker names', () => {
  assert.deepEqual(
    regionNames('// #region a\nx\n  # #region b\n// #endregion a'),
    ['a', 'b'],
  )
  assert.deepEqual(tourMarkers('text\n  <!-- tour: x.sbt -->\n'), ['x.sbt'])
})
