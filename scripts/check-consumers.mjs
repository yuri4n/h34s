import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'
import { releaseVersion } from './check-release.mjs'

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const temporary = mkdtempSync(join(tmpdir(), 'h34s-consumers-'))
const maven = join(temporary, 'maven')
const version = releaseVersion()
const published = process.argv.includes('--published')
const resolver = published
  ? '"GitHub Packages" at "https://maven.pkg.github.com/yuri4n/h34s"'
  : `Resolver.file("release-staging", file("${maven}"))(Resolver.mavenStylePatterns)`

function run(command, args, cwd) {
  const result = spawnSync(command, args, { cwd, stdio: 'inherit' })
  if (result.error)
    throw result.error
  assert.equal(
    result.status,
    0,
    `${command} failed (${result.signal || result.status})`,
  )
}

function write(path, content) {
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, content)
}

try {
  // Publish only to this disposable Maven repository. Neither GitHub credentials
  // nor any locally published Ivy artifacts are used by the consumer builds.
  if (!published) {
    run('sbt', [
      `set ThisBuild / publishTo := Some(Resolver.file("consumer-check", file("${maven}"))(Resolver.mavenStylePatterns))`,
      'releasePublish',
    ], repository)
  }
  run(
    'npm',
    [
      'install',
      '--prefix',
      temporary,
      '--no-package-lock',
      '--ignore-scripts',
      '--no-audit',
      '--no-fund',
      'h3@1.15.4',
    ],
    temporary,
  )

  for (const runtime of ['zio', 'kyo', 'zio-schema']) {
    const directory = join(temporary, runtime)
    const scalaVersion = runtime === 'kyo' ? '3.9.0' : '3.5.2'
    const effect = runtime === 'kyo' ? 'kyo' : 'zio'
    const schema = runtime === 'zio-schema'
    write(
      join(directory, '.jvmopts'),
      readFileSync(resolve(repository, '.jvmopts'), 'utf8'),
    )
    write(join(directory, 'project/build.properties'), 'sbt.version=1.11.7\n')
    write(
      join(directory, 'project/plugins.sbt'),
      'addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")\n',
    )
    write(join(directory, 'build.sbt'), `
scalaVersion := "${scalaVersion}"
enablePlugins(ScalaJSPlugin)
externalResolvers := {
  require(!sys.props.get("sbt.override.build.repos").exists(_.equalsIgnoreCase("true")), "Fresh consumer checks require their explicit Maven resolvers; disable sbt.override.build.repos in the sbt launcher.")
  Seq(${resolver}, Resolver.mavenCentral)
}
${published
  ? 'credentials ++= (for { actor <- sys.env.get("GITHUB_ACTOR").toSeq; token <- sys.env.get("GITHUB_TOKEN").toSeq } yield Credentials("GitHub Package Registry", "maven.pkg.github.com", actor, token))'
  : ''}
libraryDependencies ++= Seq(
  "dev.yuri4n" %%% "${effect}-h3-ext" % "${version}",
  "dev.yuri4n" %%% "${effect}-nuxthub-ext" % "${version}",
)
${schema ? `libraryDependencies ++= Seq("dev.yuri4n" %%% "zio-h3-schema-ext" % "${version}", "dev.yuri4n" %%% "native-converter-zio-json-ext" % "${version}", "dev.zio" %%% "zio-schema-derivation" % "1.7.5")` : ''}
scalaJSLinkerConfig ~= (_.withModuleKind(org.scalajs.linker.interface.ModuleKind.CommonJSModule).withOutputPatterns(org.scalajs.linker.interface.OutputPatterns.fromJSFile("%s.cjs")))
Compile / fastLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "out"
TaskKey[Unit]("writeDependencies") := IO.write(baseDirectory.value / "dependencies.txt", (Compile / update).value.allModules.map(m => m.organization + ":" + m.name + ":" + m.revision).sorted.mkString("\\n"))
TaskKey[Unit]("writeArtifactPaths") := IO.write(baseDirectory.value / "artifacts.txt", (Compile / update).value.allFiles.map(_.getAbsolutePath).sorted.mkString("\\n"))
`)
    write(join(directory, 'src/main/scala/Consumer.scala'), `
import scala.scalajs.js.annotation.JSExportTopLevel
import org.getshaka.nativeconverter.NativeConverter
import dev.yuri4n.exts.nuxthub.HubKv
import dev.yuri4n.exts.${runtime === 'kyo'
  ? 'kyoh3.KHandler'
  : 'zioh3.ZHandler'}
${runtime === 'kyo' ? 'import kyo.*' : 'import zio.ZIO'}
${schema ? 'import dev.yuri4n.exts.zioh3schema.*\nimport dev.yuri4n.exts.ntcvziojson.given\nimport zio.schema.{Schema, DeriveSchema}\nimport zio.json.ast.Json' : ''}
final case class Greeting(message: String) derives NativeConverter
object Consumer:
  def kvAdapter(kv: HubKv) = dev.yuri4n.exts.${effect === 'kyo' ? 'kyonuxthub.KHubKv' : 'zionuxthub.ZHubKv'}(kv)
  ${schema ? 'given Schema[Greeting] = DeriveSchema.gen[Greeting]\n  @JSExportTopLevel("jsonNumber")\n  val jsonNumber = summon[NativeConverter[Json]].toNative(Json.Num(BigDecimal("123456.789")))' : ''}
  @JSExportTopLevel("handler")
  val handler = ${runtime === 'kyo'
    ? 'KHandler.define(Sync.defer(Greeting("published-kyo")))'
    : schema ? 'ZHandler.define(ZHandler.readBodyWithSchema[Greeting])' : 'ZHandler.define(ZIO.succeed(Greeting("published-zio")))'}
`)
    run('sbt', ['compile', 'writeDependencies', 'writeArtifactPaths', 'fastLinkJS'], directory)
    const dependencies = readFileSync(
      join(directory, 'dependencies.txt'),
      'utf8',
    )
    const artifacts = readFileSync(join(directory, 'artifacts.txt'), 'utf8')
    assert(!/[/\\]local[/\\]dev\.yuri4n[/\\]/.test(artifacts), 'Consumer resolved a local Ivy h34s artifact')
    if (!published)
      assert(artifacts.includes(maven), 'Consumer did not use the isolated Maven staging repository')
    assert(
      !dependencies.includes(runtime === 'kyo' ? 'dev.zio:' : 'io.getkyo:'),
      `${runtime} consumer acquired the other effect runtime`,
    )
    assert(
      !/^org\.scalablytyped(?:\.|:)/m.test(dependencies),
      'Consumer acquired generated local-only facade artifacts',
    )
    if (!schema && runtime === 'zio') {
      assert(
        !dependencies.includes(':zio-schema'),
        'NativeConverter consumer acquired optional schema dependencies',
      )
    }
    write(join(directory, 'smoke.cjs'), `
const assert = require('node:assert/strict');
const { createServer } = require('node:http');
const { createApp, toNodeListener } = require('h3');
const { handler } = require('./out/main.cjs');
${schema ? "assert.equal(require('./out/main.cjs').jsonNumber, 123456.789);" : ''}
const app = createApp();
app.use('/hello', handler);
const server = createServer(toNodeListener(app));
server.listen(0, '127.0.0.1', async () => {
  try {
    const response = await fetch('http://127.0.0.1:' + server.address().port + '/hello', {
      signal: AbortSignal.timeout(10000),
      ${schema ? `method: 'POST', headers: { 'content-type': 'application/json' }, body: '{"message":"published-zio-schema"}',` : ''}
    });
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { message: 'published-${runtime}' });
    console.log('${runtime} published consumer served a real h3 request');
  } catch (error) {
    console.error(error);
    process.exitCode = 1;
  } finally { server.closeAllConnections(); server.close(); }
});
`)
    run(process.execPath, ['smoke.cjs'], directory)
  }
  console.log(`Fresh Maven consumers passed for ${version}`)
}
finally {
  rmSync(temporary, { recursive: true, force: true })
}
