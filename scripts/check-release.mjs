import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import process from 'node:process'
import { fileURLToPath, pathToFileURL } from 'node:url'

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '..')

/** Every release module builds with this Scala version (see build.sbt). */
export const scalaVersion = '3.9.0'

/**
 * The published library set: [directory under scala-modules/exts, artifact name, kind].
 * The kind says which effect runtime the artifact may bring to a consumer:
 * - `native`: no effect runtime (h3 bindings and shared values).
 * - `kyo`: Kyo only. A Kyo-only application never resolves ZIO.
 * - `zio-codec`: ZIO data-type converters, without Kyo.
 * - `zio`: the ZIO adapter over the Kyo core, so Kyo is a legitimate dependency.
 */
export const releaseModules = [
  ['h3', 'h3-ext', 'native'],
  ['kyo-h3', 'kyo-h3-ext', 'kyo'],
  ['kyo-nuxthub', 'kyo-nuxthub-ext', 'kyo'],
  ['native-converter-kyo-schema', 'native-converter-kyo-schema-ext', 'kyo'],
  ['zio-h3', 'zio-h3-ext', 'zio'],
  ['zio-nuxthub', 'zio-nuxthub-ext', 'zio'],
  ['native-converter-zio', 'native-converter-zio-ext', 'zio-codec'],
  ['native-converter-zio-json', 'native-converter-zio-json-ext', 'zio-codec'],
  ['native-converter-zio-schema', 'native-converter-zio-schema-ext', 'zio-codec'],
]

// Schema support stays optional: only the schema bridges may bring a schema library.
const schemaBridges = new Set(['native-converter-kyo-schema-ext', 'native-converter-zio-schema-ext'])
// Applications choose their own JSON/YAML libraries; the bridges never force one.
const formatLibraries = ['kyo-schema-json', 'kyo-schema-yaml', 'zio-schema-json']

/**
 * The reusable modules that build.sbt publishes, as [directory, artifact] pairs, read from its
 * `releaseModules` alias list and each project's `file(...)` and `name := ...` settings.
 */
export function buildReleaseModules(root = repository) {
  const build = readFileSync(resolve(root, 'build.sbt'), 'utf8')
  const list = build.match(/lazy val releaseModules\s*=\s*Seq\(([\s\S]*?)\)/)?.[1]
  assert(list, 'build.sbt must define `lazy val releaseModules = Seq(...)`')
  return [...list.matchAll(/"(\w+)"/g)].map(([, id]) => {
    const start = build.search(new RegExp(`lazy val ${id}\\b`))
    assert(start >= 0, `build.sbt: release module ${id} has no project definition`)
    const next = build.indexOf('lazy val ', start + 1)
    const project = build.slice(start, next < 0 ? undefined : next)
    const directory = project.match(/file\("scala-modules\/exts\/([^"]+)"\)/)?.[1]
    const artifact = project.match(/name\s*:=\s*"([^"]+)"/)?.[1]
    assert(directory && artifact, `build.sbt: cannot read the directory and name of ${id}`)
    return [directory, artifact]
  })
}

/**
 * The version in version.sbt. A `-SNAPSHOT` version is accepted only when `allowSnapshot` is set,
 * for pull request validation; a release always needs a concrete version.
 */
export function releaseVersion(root = repository, { allowSnapshot = false } = {}) {
  const version = readFileSync(resolve(root, 'version.sbt'), 'utf8').match(
    /ThisBuild\s*\/\s*version\s*:=\s*"([^"]+)"/,
  )?.[1]
  assert(version, 'version.sbt must set ThisBuild / version')
  assert(
    allowSnapshot || !version.endsWith('-SNAPSHOT'),
    'A concrete release version is required in version.sbt',
  )
  return version
}

function dependenciesOf(pom) {
  return [...pom.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)].map(([, dependency]) => ({
    group: dependency.match(/<groupId>(.*?)<\/groupId>/)?.[1],
    artifact: dependency.match(/<artifactId>(.*?)<\/artifactId>/)?.[1],
    version: dependency.match(/<version>(.*?)<\/version>/)?.[1],
    scope: dependency.match(/<scope>(.*?)<\/scope>/)?.[1] ?? 'compile',
  }))
}

export function checkRelease(root = repository, environment = process.env, { allowSnapshot = false } = {}) {
  const tag = environment.GITHUB_REF_TYPE === 'tag'
  // A tag always publishes, so it never accepts a snapshot.
  const version = releaseVersion(root, { allowSnapshot: allowSnapshot && !tag })
  if (tag) {
    assert.equal(
      environment.GITHUB_REF_NAME,
      `v${version}`,
      'Git tag must match version.sbt',
    )
    // release.yml creates the GitHub release from this file AFTER publishing; check it before.
    assert(
      existsSync(resolve(root, `.github/release-notes/${version}.md`)),
      `Write .github/release-notes/${version}.md before tagging`,
    )
    // claude 2026-10-19: created: the placeholder must become this version's notes (docs/releases.md, step 3)
    assert(
      !existsSync(resolve(root, '.github/release-notes/unreleased.md')),
      `.github/release-notes/unreleased.md still exists: rename it to ${version}.md before tagging`,
    )
  }

  const listed = releaseModules.map(([directory, artifact]) => `${directory}:${artifact}`).sort()
  const built = buildReleaseModules(root).map(([directory, artifact]) => `${directory}:${artifact}`).sort()
  assert.deepEqual(built, listed, 'build.sbt releaseModules and scripts/check-release.mjs list different modules')

  const kinds = new Map(releaseModules.map(([, artifact, kind]) => [`${artifact}_sjs1_3`, kind]))
  for (const [directory, artifact, kind] of releaseModules) {
    const path = resolve(
      root,
      `scala-modules/exts/${directory}/target/scala-${scalaVersion}/${artifact}_sjs1_3-${version}.pom`,
    )
    const pom = readFileSync(path, 'utf8')
    const metadata = pom.split('<dependencies>')[0]
    assert(metadata.includes(`<groupId>dev.yuri4n</groupId>`), `${artifact}: wrong organization`)
    assert(metadata.includes(`<artifactId>${artifact}_sjs1_3</artifactId>`), `${artifact}: wrong artifact`)
    assert(metadata.includes(`<version>${version}</version>`), `${artifact}: wrong version`)
    assert(!pom.includes('file:'), `${artifact}: local repository leaked into POM`)

    const dependencies = dependenciesOf(pom)
    // Test-scoped dependencies do not reach consumers.
    const published = dependencies.filter(dependency => dependency.scope !== 'test')
    const library = published.find(dependency =>
      dependency.group === 'org.scala-lang' && /^scala3-library_(?:sjs1_)?3$/.test(dependency.artifact))
    assert.equal(library?.version, scalaVersion, `${artifact}: must build with Scala ${scalaVersion}`)

    for (const dependency of dependencies) {
      assert(
        dependency.group !== 'org.scalablytyped' && !dependency.group?.startsWith('org.scalablytyped.'),
        `${artifact}: unpublished generated facade dependency`,
      )
      assert(
        !['h3_sjs1_3', 'std_sjs1_3', 'ufo_sjs1_3'].includes(dependency.artifact),
        `${artifact}: unpublished generated facade dependency`,
      )
      if (dependency.group === 'dev.yuri4n') {
        assert(
          kinds.has(dependency.artifact),
          `${artifact}: unpublished internal dependency ${dependency.artifact}`,
        )
        assert.equal(dependency.version, version, `${artifact}: inconsistent internal dependency version`)
      }
    }

    for (const dependency of published) {
      const name = `${dependency.group}:${dependency.artifact}`
      const internal = dependency.group === 'dev.yuri4n' ? kinds.get(dependency.artifact) : undefined
      if (kind === 'native') {
        assert(
          !['dev.zio', 'io.getkyo'].includes(dependency.group) && internal === undefined,
          `${artifact}: native h3 must remain effect-neutral (${name})`,
        )
      }
      if (kind === 'kyo') {
        assert(
          dependency.group !== 'dev.zio' && !['zio', 'zio-codec'].includes(internal),
          `${artifact}: Kyo modules must not depend on ZIO (${name})`,
        )
      }
      if (kind === 'zio-codec') {
        assert(
          dependency.group !== 'io.getkyo' && !['kyo', 'zio'].includes(internal),
          `${artifact}: ZIO converters must not depend on Kyo (${name})`,
        )
      }
      if (!schemaBridges.has(artifact)) {
        assert(
          !/^(?:zio-schema|kyo-schema)/.test(dependency.artifact) && !schemaBridges.has(dependency.artifact?.replace(/_sjs1_3$/, '')),
          `${artifact}: schema support must stay optional (${name})`,
        )
      }
      assert(
        !formatLibraries.some(format => dependency.artifact?.startsWith(`${format}_`)),
        `${artifact}: must not choose a JSON/YAML library for the application (${name})`,
      )
    }
    console.log(`Validated ${artifact}_sjs1_3:${version}`)
  }
  return version
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href)
  checkRelease(repository, process.env, { allowSnapshot: process.argv.includes('--allow-snapshot') })
