import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
// eslint-disable-next-line test/no-import-node-test
import { test } from 'node:test'
import { checkRelease, releaseModules, releaseVersion } from './check-release.mjs'

function fixture(t, firstDependency = '', version = '0.1.0-alpha.1', dependencyModule = 'h3') {
  const root = mkdtempSync(join(tmpdir(), 'h34s-release-metadata-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  writeFileSync(join(root, 'version.sbt'), `ThisBuild / version := "${version}"\n`)
  for (const [directory, artifact, scalaVersion] of releaseModules) {
    const path = join(root, `scala-modules/exts/${directory}/target/scala-${scalaVersion}/${artifact}_sjs1_3-${version}.pom`)
    mkdirSync(dirname(path), { recursive: true })
    writeFileSync(path, `<project><groupId>dev.yuri4n</groupId><artifactId>${artifact}_sjs1_3</artifactId><version>${version}</version><dependencies>${directory === dependencyModule ? firstDependency : ''}</dependencies></project>`)
  }
  return root
}

test('publication refuses tags that do not match the concrete release version', (t) => {
  assert.throws(() => checkRelease(fixture(t), { GITHUB_REF_TYPE: 'tag', GITHUB_REF_NAME: 'v0.1.0-alpha.2' }), /Git tag must match/)
})

test('publication refuses generated ScalablyTyped dependencies even with arbitrary artifact names', (t) => {
  for (const group of ['org.scalablytyped', 'org.scalablytyped.custom.namespace']) {
    const root = fixture(t, `<dependency><groupId>${group}</groupId><artifactId>unpublished_sjs1_3</artifactId><version>1-hash</version></dependency>`)
    assert.throws(() => checkRelease(root, {}), /unpublished generated facade/)
  }
})

test('publication refuses inconsistent internal dependency versions', (t) => {
  const root = fixture(t, '<dependency><groupId>dev.yuri4n</groupId><artifactId>zio-js-ext_sjs1_3</artifactId><version>0.1.0</version></dependency>')
  assert.throws(() => checkRelease(root, {}), /inconsistent internal dependency version/)
})

test('snapshot versions require an explicit local validation option and cannot pass a tag check', (t) => {
  const root = fixture(t, '', '0.1.0-alpha.2-SNAPSHOT')
  assert.throws(() => releaseVersion(root), /concrete release version/)
  assert.equal(releaseVersion(root, { allowSnapshot: true }), '0.1.0-alpha.2-SNAPSHOT')
  assert.equal(checkRelease(root, {}, { allowSnapshot: true }), '0.1.0-alpha.2-SNAPSHOT')
  assert.throws(() => checkRelease(root, { GITHUB_REF_TYPE: 'tag', GITHUB_REF_NAME: 'v0.1.0-alpha.2-SNAPSHOT' }, { allowSnapshot: true }), /concrete release version/)
})

test('Cats bridge metadata rejects an accidental transport dependency', (t) => {
  const root = fixture(t, '<dependency><groupId>dev.yuri4n</groupId><artifactId>h3-ext_sjs1_3</artifactId><version>0.1.0-alpha.1</version></dependency>', '0.1.0-alpha.1', 'kyo-cats')
  assert.throws(() => checkRelease(root, {}), /must not depend on h3 or a database adapter/)
})
