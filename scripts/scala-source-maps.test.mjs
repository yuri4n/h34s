import assert from 'node:assert/strict'
import {
  mkdir,
  mkdtemp,
  readFile,
  rm,
  symlink,
  writeFile,
} from 'node:fs/promises'
import { SourceMap } from 'node:module'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
// These build-tool checks run directly in Node without the Nuxt/Vitest runtime.
// eslint-disable-next-line test/no-import-node-test
import test from 'node:test'
import { pathToFileURL } from 'node:url'
import { scalaSourceMaps } from '@yuri4n/h34s-nuxt/rollup'

async function fixture(
  t,
  sources,
  sourceRoot,
  outputDirectory = 'server/.scala',
) {
  const root = await mkdtemp(join(tmpdir(), 'h34s-source-maps-'))
  t.after(() => rm(root, { recursive: true, force: true }))
  const linked = join(root, outputDirectory, 'main.js')
  const scala = join(root, 'Main.scala')
  await mkdir(join(root, outputDirectory), { recursive: true })
  await writeFile(scala, 'object Main:\n  throw new Exception("debug probe")\n')
  await writeFile(linked, 'throw new Error("debug probe");\n')
  const rawMap = {
    version: 3,
    sources: sources ?? [pathToFileURL(scala).href],
    sourceRoot,
    names: [],
    mappings: 'AACA',
  }
  await writeFile(`${linked}.map`, JSON.stringify(rawMap))
  const watched = []
  const context = { addWatchFile: file => watched.push(file) }
  const load = id => scalaSourceMaps(root).load.call(context, id)
  return { root, linked, scala, watched, load }
}

test('maps JS to the original Scala line and content', async (t) => {
  const f = await fixture(t)
  const result = await f.load(f.linked)
  assert.equal(result.code, await readFile(f.linked, 'utf8'))
  assert.deepEqual(f.watched, [`${f.linked}.map`])
  assert.deepEqual(result.map.sources, [f.scala])
  const content = await readFile(f.scala, 'utf8')
  assert.deepEqual(result.map.sourcesContent, [content])
  const entry = new SourceMap(result.map).findEntry(0, 0)
  assert.equal(entry.originalSource, f.scala)
  assert.equal(entry.originalLine, 1)
})

test('resolves relative sources and sourceRoot', async (t) => {
  const f = await fixture(t, ['Main.scala'], '../../')
  const result = await f.load(f.linked)
  assert.deepEqual(result.map.sources, [f.scala])
  assert.ok(result.map.sourcesContent[0].includes('debug probe'))
  assert.equal(result.map.sourceRoot, undefined)
})

test('keeps external sources without reading or fetching them', async (t) => {
  const remote = 'https://invalid.example/Dependency.scala'
  const outside = 'file:///outside-project/Private.scala'
  const f = await fixture(t, [remote, outside])
  const result = await f.load(f.linked)
  const expected = [remote, '/outside-project/Private.scala']
  assert.deepEqual(result.map.sources, expected)
  assert.deepEqual(result.map.sourcesContent, [null, null])
})

test('does not embed project symlinks to external sources', async (t) => {
  const outside = await fixture(t)
  const f = await fixture(t, ['Linked.scala'], '../../')
  await symlink(outside.scala, join(f.root, 'Linked.scala'))
  const result = await f.load(f.linked)
  assert.deepEqual(result.map.sourcesContent, [null])
})

test('leaves unrelated JS and virtual modules to Nitro', async (t) => {
  const f = await fixture(t)
  assert.equal(await f.load(join(f.root, 'server/other.js')), null)
  const otherDirectory = join(f.root, 'server/.scala-other/main.js')
  assert.equal(await f.load(otherDirectory), null)
  assert.equal(await f.load('\0virtual'), null)
  assert.deepEqual(f.watched, [])
})

// claude 2026-10-20: created: sourceRoots, such as the sbt root of layout B or C (ADR-0009)
test('embeds Scala sources inside sourceRoots, and skips a source root that does not exist', async (t) => {
  const f = await fixture(t)
  const build = await mkdtemp(join(tmpdir(), 'h34s-source-maps-build-'))
  t.after(() => rm(build, { recursive: true, force: true }))
  const scala = join(build, 'Server.scala')
  await writeFile(scala, 'object Server\n')
  await writeFile(`${f.linked}.map`, JSON.stringify({ version: 3, sources: [pathToFileURL(scala).href, pathToFileURL(f.scala).href], names: [], mappings: 'AACA' }))
  const load = sourceRoots => scalaSourceMaps(f.root, undefined, sourceRoots).load.call({ addWatchFile() {} }, f.linked)
  const project = await readFile(f.scala, 'utf8')
  assert.deepEqual((await load([build])).map.sourcesContent, ['object Server\n', project])
  assert.deepEqual((await load([])).map.sourcesContent, [null, project], 'without the source root, only the project root embeds')
  assert.deepEqual((await load([join(build, 'missing')])).map.sourcesContent, [null, project])
})
