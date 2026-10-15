import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import process from 'node:process'
import { fileURLToPath, pathToFileURL } from 'node:url'

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '..')

export const releaseModules = [
  ['h3', 'h3-ext', '3.5.2'],
  ['zio-h3', 'zio-h3-ext', '3.5.2'],
  ['kyo-h3', 'kyo-h3-ext', '3.9.0'],
  ['zio-js', 'zio-js-ext', '3.5.2'],
  ['native-converter-zio', 'native-converter-zio-ext', '3.5.2'],
  ['native-converter-zio-json', 'native-converter-zio-json-ext', '3.5.2'],
  ['native-converter-zio-schema', 'native-converter-zio-schema-ext', '3.5.2'],
  ['zio-h3-schema', 'zio-h3-schema-ext', '3.5.2'],
]

export function releaseVersion(root = repository) {
  const version = readFileSync(resolve(root, 'version.sbt'), 'utf8').match(
    /ThisBuild\s*\/\s*version\s*:=\s*"([^"]+)"/,
  )?.[1]
  assert(
    version && !version.endsWith('-SNAPSHOT'),
    'A concrete release version is required in version.sbt',
  )
  return version
}

export function checkRelease(root = repository, environment = process.env) {
  const version = releaseVersion(root)
  if (environment.GITHUB_REF_TYPE === 'tag') {
    assert.equal(
      environment.GITHUB_REF_NAME,
      `v${version}`,
      'Git tag must match version.sbt',
    )
  }

  const expectedArtifacts = new Set(
    releaseModules.map(([, artifact]) => `${artifact}_sjs1_3`),
  )
  for (const [directory, artifact, scalaVersion] of releaseModules) {
    const path = resolve(
      root,
      `scala-modules/exts/${directory}/target/scala-${scalaVersion}/${artifact}_sjs1_3-${version}.pom`,
    )
    const pom = readFileSync(path, 'utf8')
    const dependencies = [
      ...pom.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g),
    ].map(([, dependency]) => ({
      group: dependency.match(/<groupId>(.*?)<\/groupId>/)?.[1],
      artifact: dependency.match(/<artifactId>(.*?)<\/artifactId>/)?.[1],
      version: dependency.match(/<version>(.*?)<\/version>/)?.[1],
    }))
    const metadata = pom.split('<dependencies>')[0]
    assert(
      metadata.includes(`<groupId>dev.yuri4n</groupId>`),
      `${artifact}: wrong organization`,
    )
    assert(
      metadata.includes(`<artifactId>${artifact}_sjs1_3</artifactId>`),
      `${artifact}: wrong artifact`,
    )
    assert(
      metadata.includes(`<version>${version}</version>`),
      `${artifact}: wrong version`,
    )
    assert(
      !pom.includes('file:'),
      `${artifact}: local repository leaked into POM`,
    )
    for (const dependency of dependencies) {
      assert(
        dependency.group !== 'org.scalablytyped' && !dependency.group?.startsWith(
          'org.scalablytyped.',
        ),
        `${artifact}: unpublished generated facade dependency`,
      )
      assert(
        !['h3_sjs1_3', 'std_sjs1_3', 'ufo_sjs1_3'].includes(
          dependency.artifact,
        ),
        `${artifact}: unpublished generated facade dependency`,
      )
      if (dependency.group === 'dev.yuri4n') {
        assert(
          expectedArtifacts.has(dependency.artifact),
          `${artifact}: unpublished internal dependency ${dependency.artifact}`,
        )
        assert.equal(
          dependency.version,
          version,
          `${artifact}: inconsistent internal dependency version`,
        )
      }
      if (artifact === 'h3-ext') {
        assert(
          !['dev.zio', 'io.getkyo'].includes(dependency.group),
          'Native h3 must remain effect-neutral',
        )
      }
      if (artifact === 'kyo-h3-ext')
        assert(dependency.group !== 'dev.zio', 'Kyo must not depend on ZIO')
      if (artifact === 'zio-h3-ext') {
        assert(
          dependency.group !== 'io.getkyo' && !dependency.artifact?.startsWith(
            'zio-schema',
          ),
          'ZIO NativeConverter API must not depend on Kyo or Schema',
        )
      }
    }
    console.log(`Validated ${artifact}_sjs1_3:${version}`)
  }
  return version
}

if (process.argv[1] && import.meta.url === pathToFileURL(
  resolve(process.argv[1]),
).href) { checkRelease() }
