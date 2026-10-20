import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
// eslint-disable-next-line test/no-import-node-test
import { test } from 'node:test'
import { buildReleaseModules, checkRelease, releaseModules, scalaVersion } from './check-release.mjs'

const scalaLibrary = version => dependency('org.scala-lang', 'scala3-library_sjs1_3', version)

function dependency(group, artifact, version, scope) {
  return `<dependency><groupId>${group}</groupId><artifactId>${artifact}</artifactId><version>${version}</version>${scope ? `<scope>${scope}</scope>` : ''}</dependency>`
}

function camel(directory) {
  return directory.replace(/-(\w)/g, (_, letter) => letter.toUpperCase())
}

/**
 * A repository with version.sbt, a build.sbt that lists the release modules, and one POM per
 * module. `extra` maps a module directory to additional POM dependencies.
 */
function fixture(t, { version = '0.2.0', extra = {}, modules = releaseModules, library = scalaVersion } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'h34s-release-metadata-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  writeFileSync(join(root, 'version.sbt'), `ThisBuild / version := "${version}"\n`)
  const ids = modules.map(([directory]) => `"${camel(directory)}"`).join(', ')
  const projects = modules.map(([directory, artifact]) =>
    `lazy val ${camel(directory)}: Project = (project in file("scala-modules/exts/${directory}"))\n  .settings(name := "${artifact}")\n`)
  writeFileSync(join(root, 'build.sbt'), `lazy val releaseModules = Seq(\n  ${ids},\n)\n${projects.join('\n')}`)
  for (const [directory, artifact] of releaseModules) {
    const path = join(root, `scala-modules/exts/${directory}/target/scala-${scalaVersion}/${artifact}_sjs1_3-${version}.pom`)
    mkdirSync(dirname(path), { recursive: true })
    writeFileSync(path, `<project><groupId>dev.yuri4n</groupId><artifactId>${artifact}_sjs1_3</artifactId><version>${version}</version><dependencies>${scalaLibrary(library)}${extra[directory] ?? ''}</dependencies></project>`)
  }
  return root
}

test('build.sbt and the script list the same release modules', () => {
  const listed = releaseModules.map(([directory, artifact]) => `${directory}:${artifact}`).sort()
  assert.deepEqual(buildReleaseModules().map(([directory, artifact]) => `${directory}:${artifact}`).sort(), listed)
})

test('a consistent release passes, with the allowed cross-runtime dependencies', (t) => {
  const root = fixture(t, {
    extra: {
      // ZIO consumers legitimately get Kyo through the adapter.
      'zio-h3': dependency('dev.yuri4n', 'kyo-h3-ext_sjs1_3', '0.2.0') + dependency('io.getkyo', 'kyo-zio_sjs1_3', '1.0.0-RC7'),
      // Test-scoped dependencies never reach consumers.
      'kyo-h3': dependency('dev.zio', 'zio-test_sjs1_3', '2.1.26', 'test'),
      'native-converter-kyo-schema': dependency('io.getkyo', 'kyo-schema_sjs1_3', '1.0.0-RC7'),
    },
  })
  assert.equal(checkRelease(root, {}), '0.2.0')
})

test('publication refuses tags that do not match the concrete release version', (t) => {
  assert.throws(() => checkRelease(fixture(t), { GITHUB_REF_TYPE: 'tag', GITHUB_REF_NAME: 'v0.2.1' }), /Git tag must match/)
})

test('a tag needs its release notes before anything is published', (t) => {
  const root = fixture(t)
  const tag = { GITHUB_REF_TYPE: 'tag', GITHUB_REF_NAME: 'v0.2.0' }
  assert.throws(() => checkRelease(root, tag), /Write \.github\/release-notes\/0\.2\.0\.md before tagging/)
  mkdirSync(join(root, '.github/release-notes'), { recursive: true })
  writeFileSync(join(root, '.github/release-notes/0.2.0.md'), 'Notes\n')
  assert.equal(checkRelease(root, tag), '0.2.0')
})

// claude 2026-10-19: created: a tag must not leave the placeholder notes behind
test('a tag refuses the placeholder notes that were not renamed', (t) => {
  const root = fixture(t)
  const tag = { GITHUB_REF_TYPE: 'tag', GITHUB_REF_NAME: 'v0.2.0' }
  mkdirSync(join(root, '.github/release-notes'), { recursive: true })
  writeFileSync(join(root, '.github/release-notes/0.2.0.md'), 'Notes\n')
  writeFileSync(join(root, '.github/release-notes/unreleased.md'), 'Placeholder\n')
  assert.throws(() => checkRelease(root, tag), /unreleased\.md still exists/)
  // Pull request validation keeps accepting the placeholder while work lands.
  assert.equal(checkRelease(root, {}), '0.2.0')
  rmSync(join(root, '.github/release-notes/unreleased.md'))
  assert.equal(checkRelease(root, tag), '0.2.0')
})

test('a snapshot passes only pull request validation, never a tag', (t) => {
  const root = fixture(t, { version: '0.2.0-SNAPSHOT' })
  assert.throws(() => checkRelease(root, {}), /concrete release version/)
  assert.equal(checkRelease(root, {}, { allowSnapshot: true }), '0.2.0-SNAPSHOT')
  assert.throws(
    () => checkRelease(root, { GITHUB_REF_TYPE: 'tag', GITHUB_REF_NAME: 'v0.2.0-SNAPSHOT' }, { allowSnapshot: true }),
    /concrete release version/,
  )
})

test('publication refuses generated ScalablyTyped dependencies even with arbitrary artifact names', (t) => {
  for (const group of ['org.scalablytyped', 'org.scalablytyped.custom.namespace']) {
    const root = fixture(t, { extra: { h3: dependency(group, 'unpublished_sjs1_3', '1-hash') } })
    assert.throws(() => checkRelease(root, {}), /unpublished generated facade/)
  }
})

test('publication refuses inconsistent internal dependency versions', (t) => {
  const root = fixture(t, { extra: { 'kyo-nuxthub': dependency('dev.yuri4n', 'kyo-h3-ext_sjs1_3', '0.1.0') } })
  assert.throws(() => checkRelease(root, {}), /inconsistent internal dependency version/)
})

test('publication refuses removed or unknown internal modules', (t) => {
  const root = fixture(t, { extra: { 'zio-h3': dependency('dev.yuri4n', 'zio-js-ext_sjs1_3', '0.2.0') } })
  assert.throws(() => checkRelease(root, {}), /unpublished internal dependency zio-js-ext_sjs1_3/)
})

test('Kyo modules never depend on ZIO, directly or through an h34s ZIO module', (t) => {
  for (const extra of [
    { 'kyo-h3': dependency('dev.zio', 'zio_sjs1_3', '2.1.26') },
    { 'kyo-nuxthub': dependency('dev.yuri4n', 'zio-h3-ext_sjs1_3', '0.2.0') },
    { 'native-converter-kyo-schema': dependency('dev.yuri4n', 'native-converter-zio-ext_sjs1_3', '0.2.0') },
  ])
    assert.throws(() => checkRelease(fixture(t, { extra }), {}), /Kyo modules must not depend on ZIO/)
})

test('the native layer stays effect-neutral and ZIO converters stay Kyo-free', (t) => {
  assert.throws(
    () => checkRelease(fixture(t, { extra: { h3: dependency('io.getkyo', 'kyo-core_sjs1_3', '1.0.0-RC7') } }), {}),
    /effect-neutral/,
  )
  assert.throws(
    () => checkRelease(fixture(t, { extra: { 'native-converter-zio': dependency('io.getkyo', 'kyo-core_sjs1_3', '1.0.0-RC7') } }), {}),
    /must not depend on Kyo/,
  )
})

test('schema libraries stay optional and the bridges do not choose a JSON/YAML library', (t) => {
  assert.throws(
    () => checkRelease(fixture(t, { extra: { 'zio-h3': dependency('dev.yuri4n', 'native-converter-zio-schema-ext_sjs1_3', '0.2.0') } }), {}),
    /schema support must stay optional/,
  )
  assert.throws(
    () => checkRelease(fixture(t, { extra: { 'kyo-h3': dependency('io.getkyo', 'kyo-schema_sjs1_3', '1.0.0-RC7') } }), {}),
    /schema support must stay optional/,
  )
  assert.throws(
    () => checkRelease(fixture(t, { extra: { 'native-converter-kyo-schema': dependency('io.getkyo', 'kyo-schema-json_sjs1_3', '1.0.0-RC7') } }), {}),
    /JSON\/YAML library/,
  )
})

test('every module builds with the one Scala version', (t) => {
  assert.throws(() => checkRelease(fixture(t, { library: '3.5.2' }), {}), /must build with Scala 3\.9\.0/)
})

test('build.sbt and the checked module list must agree', (t) => {
  const root = fixture(t, { modules: releaseModules.filter(([directory]) => directory !== 'kyo-nuxthub') })
  assert.throws(() => checkRelease(root, {}), /list different modules/)
})
