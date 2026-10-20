// The init templates equal the guide blocks that the homepage tour shows, so the scaffold, the
// guides and the tour cannot drift (ADR-0009, issue #54).
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
// The package uses Node's runner without additional test dependencies.
// eslint-disable-next-line test/no-import-node-test
import test from 'node:test'
import { extractMarkedFence, extractRegion, zioBuild } from '../../../docs/snippets/extract.mjs'
import * as buildProperties from '../src/init/templates/build-properties.mjs'
import * as h34sConfig from '../src/init/templates/h34s-config.mjs'
import * as helloScala from '../src/init/templates/hello-scala.mjs'
import * as jvmopts from '../src/init/templates/jvmopts.mjs'
import * as npmrc from '../src/init/templates/npmrc.mjs'
import * as packageJson from '../src/init/templates/package-json.mjs'
import * as pluginsSbt from '../src/init/templates/plugins-sbt.mjs'
import { stripRegions } from '../src/init/templates/regions.mjs'
import * as sbtBuild from '../src/init/templates/sbt-build.mjs'

// claude 2026-10-20: created: the guides and the package version (ADR-0009)
const guide = path => readFileSync(new URL(`../../../docs/content/${path}`, import.meta.url), 'utf8')
const adoption = '1.guide/02.adoption.md'
const npmTooling = '1.guide/13.npm-tooling.md'
const releases = '2.reference/2.releases.md'
const version = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version

// claude 2026-10-20: created: the parameters of layout A (ADR-0009)
/** The parameters of the Kyo layout-A rendering, the one the guides show. */
const layoutA = { api: 'kyo', version, project: 'server', scalaDir: 'scala-server', appDir: '', sbtRoot: '.', sbtFile: 'build.sbt', registry: true, scalaPackage: 'app' }
// claude 2026-10-20: created: the parameters of layout B (ADR-0009)
/** Layout B: the Nuxt app in web/ below an existing sbt build. */
const layoutB = { ...layoutA, appDir: 'web', sbtRoot: '..', sbtFile: 'h34s.sbt' }

// claude 2026-10-20: created: a guide block without a tour marker (ADR-0009)
/**
 * The body of the one code fence in a guide whose text satisfies `match`, without the fence's
 * indentation. For the guide blocks that have no tour marker.
 */
function fence(markdown, match, file) {
  const blocks = []
  const lines = markdown.split('\n')
  for (let index = 0; index < lines.length; index++) {
    const open = /^([ \t]*)(`{3,})\w*\s*$/.exec(lines[index])
    if (!open)
      continue
    const [, indent, ticks] = open
    const body = []
    while (++index < lines.length && lines[index].trim() !== ticks)
      body.push(lines[index].startsWith(indent) ? lines[index].slice(indent.length) : lines[index].trimStart())
    blocks.push(body.join('\n'))
  }
  const found = blocks.filter(match)
  assert.equal(found.length, 1, `${file}: ${found.length} matching code fences`)
  return found[0]
}

// claude 2026-10-20: created: the test "the Kyo layout-A templates equal the tour blocks of the ..." (ADR-0009)
test('the Kyo layout-A templates equal the tour blocks of the adoption guide', () => {
  const markdown = guide(adoption)
  for (const [name, text] of [
    ['project/build.properties', buildProperties.render()],
    ['project/plugins.sbt', pluginsSbt.render()],
    ['.jvmopts', jvmopts.render()],
    ['build.sbt', sbtBuild.render(layoutA)],
    ['h34s.config.mjs', h34sConfig.render(layoutA)],
  ]) {
    assert.equal(extractRegion(text, 'tour', name), extractMarkedFence(markdown, name, adoption), name)
  }
})

// claude 2026-10-20: created: the test "the whole written file is the guide block where the ..." (ADR-0009)
test('the whole written file is the guide block where the guide shows the whole file', () => {
  const markdown = guide(adoption)
  for (const [name, text] of [
    ['project/build.properties', buildProperties.render()],
    ['project/plugins.sbt', pluginsSbt.render()],
    ['.jvmopts', jvmopts.render()],
    ['h34s.config.mjs', h34sConfig.render(layoutA)],
  ]) {
    assert.equal(stripRegions(text), `${extractMarkedFence(markdown, name, adoption)}\n`, name)
  }
})

// claude 2026-10-20: created: the test "the ZIO build.sbt follows the adoption guide's rule: kyo-h3-ext becomes ..." (ADR-0009)
test('the ZIO build.sbt follows the adoption guide\'s rule: kyo-h3-ext becomes zio-h3-ext', () => {
  const kyo = extractMarkedFence(guide(adoption), 'build.sbt', adoption)
  assert.equal(extractRegion(sbtBuild.render({ ...layoutA, api: 'zio' }), 'tour', 'build.sbt'), zioBuild(kyo, adoption))
})

// claude 2026-10-20: created: the test "the layout-B templates equal the adoption guide's path-3 blocks" (ADR-0009)
test('the layout-B templates equal the adoption guide\'s path-3 blocks', () => {
  const markdown = guide(adoption)
  assert.equal(
    extractRegion(sbtBuild.render(layoutB), 'tour', 'h34s.sbt'),
    fence(markdown, block => block.startsWith('// h34s.sbt\n'), adoption),
  )
  assert.equal(
    extractRegion(h34sConfig.render(layoutB), 'tour', 'h34s.config.mjs'),
    fence(markdown, block => block.includes('sbtRoot: \'..\'') && block.startsWith('import { defineConfig }'), adoption),
  )
})

// claude 2026-10-20: created: the test "the Kyo hello handler equals the adoption guide's handler" (ADR-0009)
test('the Kyo hello handler equals the adoption guide\'s handler', () => {
  assert.equal(
    extractRegion(helloScala.render(layoutA), 'tour', 'Hello.scala'),
    fence(guide(adoption), block => block.includes('@JSExportTopLevel("default", "hello-get")'), adoption),
  )
})

// claude 2026-10-20: created: the test "the scripts equal the npm tooling guide's package.json block, and ..." (ADR-0009)
test('the scripts equal the npm tooling guide\'s package.json block, and .npmrc its registry block', () => {
  const markdown = guide(npmTooling)
  assert.equal(packageJson.scriptsBlock(), extractMarkedFence(markdown, 'package.json', npmTooling))
  assert.equal(
    npmrc.render(),
    `${fence(markdown, block => block.startsWith('@yuri4n:registry='), npmTooling)}\n`,
  )
})

// claude 2026-10-20: created: the test "the sbt registry block equals the release guide's resolver and ..." (ADR-0009)
test('the sbt registry block equals the release guide\'s resolver and credentials', () => {
  assert.equal(
    extractRegion(sbtBuild.render(layoutA), 'registry', 'build.sbt'),
    fence(guide(releases), block => block.startsWith('ThisBuild / resolvers'), releases),
  )
  assert.equal(sbtBuild.render({ ...layoutA, registry: false }).includes('ThisBuild /'), false)
})
