import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import {
  copyFileSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import process from 'node:process'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { releaseVersion, scalaVersion } from './check-release.mjs'

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const published = process.argv.includes('--published')
// Local staging may check a snapshot; the published registry check needs the release version.
const version = releaseVersion(repository, { allowSnapshot: !published })
const temporary = mkdtempSync(join(tmpdir(), 'h34s-consumers-'))
const { GITHUB_TOKEN: _token, GITHUB_ACTOR: _actor, ...withoutCredentials } = process.env
const maven = join(temporary, 'maven')
const resolver = published
  ? '"GitHub Packages" at "https://maven.pkg.github.com/yuri4n/h34s"'
  : `"release-staging" at "${pathToFileURL(`${maven}/`).href}"`

const internalError = { errors: [{ message: 'Internal server error' }] }

/** One error entry at `path`; ZIO Schema builds its message from the check, so only its type is fixed. */
function onePathEntry(path) {
  return `(body) => {
    assert.equal(body.errors.length, 1);
    assert.equal(body.errors[0].path, ${JSON.stringify(path)});
    assert.equal(typeof body.errors[0].message, 'string');
  }`
}

/** Error entries carry messages only: no input values, no diagnostics. */
const onlyMessages = `(body) => {
    assert.ok(body.errors.length > 0);
    for (const entry of body.errors) assert.deepEqual(Object.keys(entry), ['message']);
  }`

/**
 * Independent consumer projects. Each one depends only on release coordinates, compiles, links,
 * checks what it resolved and serves real h3 requests through a preemptive router (as Nitro does).
 *
 * - `forbidden`: dependency prefixes (`group:artifact`) the consumer must not resolve. A Kyo-only
 *   consumer never resolves ZIO. A ZIO consumer resolves Kyo through the adapter, which is expected.
 *   Schema libraries arrive only with the schema bridge the consumer chose.
 * - `requests`: [method, path, body, status, expected JSON or a check function].
 */
const consumers = [
  {
    name: 'zio',
    libraries: ['zio-h3-ext', 'zio-nuxthub-ext'],
    forbidden: ['dev.zio:zio-schema', 'io.getkyo:kyo-schema', 'dev.yuri4n:native-converter-zio-schema', 'dev.yuri4n:native-converter-kyo-schema'],
    source: `
import dev.yuri4n.exts.zioh3.*
import dev.yuri4n.exts.zionuxthub.ZHubKv
import org.getshaka.nativeconverter.NativeConverter
import zio.ZIO

final case class Greeting(message: String) derives NativeConverter

object Consumer:
  @JSExportTopLevel("hello")
  val hello: EventHandler = ZHandler.define(_ => ZIO.succeed(Greeting("published-zio")))

  @JSExportTopLevel("kv")
  val kv: EventHandler = ZHandler.define: _ =>
    for
      store  <- ZHubKv.mount("kv")
      _      <- store.set("greeting", Greeting("stored-zio"))
      stored <- store.get[Greeting]("greeting")
    yield stored.getOrElse(Greeting("missing"))

  @JSExportTopLevel("fail")
  val fail: EventHandler = ZHandler.define(_ => ZIO.fail(RuntimeException("internal detail")).as(Greeting("unreachable")))
`,
    requests: [
      ['GET', '/hello', undefined, 200, { data: { message: 'published-zio' } }],
      ['GET', '/kv', undefined, 200, { data: { message: 'stored-zio' } }],
      ['GET', '/fail', undefined, 500, internalError],
    ],
  },
  {
    name: 'kyo',
    libraries: ['kyo-h3-ext', 'kyo-nuxthub-ext'],
    forbidden: ['dev.zio:', 'io.getkyo:kyo-zio', 'io.getkyo:kyo-schema', 'dev.yuri4n:zio-', 'dev.yuri4n:native-converter-'],
    source: `
import dev.yuri4n.exts.kyoh3.*
import dev.yuri4n.exts.kyonuxthub.KHubKv
import kyo.*
import org.getshaka.nativeconverter.NativeConverter

final case class Greeting(message: String) derives NativeConverter

object Consumer:
  @JSExportTopLevel("hello")
  val hello: EventHandler = KHandler.define(_ => Greeting("published-kyo"))

  @JSExportTopLevel("kv")
  val kv: EventHandler = KHandler.define: _ =>
    for
      store  <- KHubKv.mount("kv")
      _      <- store.set("greeting", Greeting("stored-kyo"))
      stored <- store.get[Greeting]("greeting")
    yield stored.getOrElse(Greeting("missing"))

  @JSExportTopLevel("fail")
  val fail: EventHandler = KHandler.define(_ => Abort.fail(RuntimeException("internal detail")).map(_ => Greeting("unreachable")))
`,
    requests: [
      ['GET', '/hello', undefined, 200, { data: { message: 'published-kyo' } }],
      ['GET', '/kv', undefined, 200, { data: { message: 'stored-kyo' } }],
      ['GET', '/fail', undefined, 500, internalError],
    ],
  },
  {
    name: 'zio-schema',
    libraries: ['zio-h3-ext', 'native-converter-zio-schema-ext', 'native-converter-zio-json-ext'],
    extra: ['"dev.zio" %%% "zio-schema-derivation" % "1.7.5"'],
    forbidden: ['io.getkyo:kyo-schema', 'dev.yuri4n:native-converter-kyo-schema-ext'],
    source: `
import dev.yuri4n.exts.ntcvziojson.given
import dev.yuri4n.exts.ntcvzioschema.NativeSchema
import dev.yuri4n.exts.zioh3.*
import org.getshaka.nativeconverter.NativeConverter
import zio.json.ast.Json
import zio.schema.{Schema, derived}
import zio.schema.annotation.validate
import zio.schema.validation.Validation

final case class Greeting(message: String) derives Schema, NativeSchema
final case class GreetingRequest(@validate(Validation.minLength(1)) name: String) derives Schema, NativeSchema

object Consumer:
  @JSExportTopLevel("jsonNumber")
  val jsonNumber = summon[NativeConverter[Json]].toNative(Json.Num(BigDecimal("123456.789")))

  @JSExportTopLevel("hello")
  val hello: EventHandler = ZHandler.define: request =>
    request.body.json[GreetingRequest].map(input => Greeting(s"published-\${input.name}"))
`,
    requests: [
      ['POST', '/hello', '{"name":"zio-schema"}', 200, { data: { message: 'published-zio-schema' } }],
      ['POST', '/hello', '{"name":""}', 422, onePathEntry('name')],
      ['POST', '/hello', '{"name":42}', 422, { errors: [{ message: 'Request body does not match the expected shape' }] }],
      ['POST', '/hello', '{not json', 400, onlyMessages],
    ],
    exports: { jsonNumber: 123456.789 },
  },
  {
    name: 'kyo-schema',
    libraries: ['kyo-h3-ext', 'native-converter-kyo-schema-ext'],
    forbidden: ['dev.zio:', 'io.getkyo:kyo-zio', 'dev.yuri4n:zio-', 'dev.yuri4n:native-converter-zio'],
    source: `
import dev.yuri4n.exts.kyoh3.*
import dev.yuri4n.exts.ntcvkyoschema.NativeSchema
import kyo.*

final case class Greeting(message: String) derives Schema, NativeSchema
final case class GreetingRequest(name: String) derives NativeSchema

object GreetingRequest:
  given Schema[GreetingRequest] = Schema[GreetingRequest].check(_.name)(_.trim.nonEmpty, "must not be blank")

object Consumer:
  @JSExportTopLevel("hello")
  val hello: EventHandler = KHandler.define: request =>
    request.body.json[GreetingRequest].map(input => Greeting(s"published-\${input.name}"))
`,
    requests: [
      ['POST', '/hello', '{"name":"kyo-schema"}', 200, { data: { message: 'published-kyo-schema' } }],
      ['POST', '/hello', '{"name":" "}', 422, { errors: [{ message: 'must not be blank', path: 'name' }] }],
      ['POST', '/hello', '{"name":42}', 422, { errors: [{ message: 'Request body does not match the expected shape' }] }],
      ['POST', '/hello', '{not json', 400, onlyMessages],
    ],
  },
]

function run(command, args, cwd, env = process.env) {
  const result = spawnSync(command, args, { cwd, env, stdio: 'inherit' })
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

function smoke(consumer) {
  const expectation = expected => typeof expected === 'string'
    ? `(${expected})`
    : `((body) => assert.deepEqual(body, ${JSON.stringify(expected)}))`
  const requests = consumer.requests.map(([method, path, body, status, expected]) =>
    `[${JSON.stringify(method)}, ${JSON.stringify(path)}, ${JSON.stringify(body)}, ${status}, ${expectation(expected)}]`)
  const routes = [...new Set(consumer.requests.map(([, path]) => path.slice(1)))]
  return `
const assert = require('node:assert/strict');
const { createServer } = require('node:http');
const { createApp, createRouter, toNodeListener } = require('h3');
const linked = require('./out/main.cjs');
for (const [name, value] of Object.entries(${JSON.stringify(consumer.exports ?? {})}))
  assert.deepEqual(linked[name], value, name);

// The startup record of the h34s Nitro plugin, with one in-memory storage mount "kv".
const values = new Map();
const kv = {
  get: async key => values.has(key) ? values.get(key) : null,
  set: async (key, value) => { values.set(key, value) },
  has: async key => values.has(key),
  del: async key => { values.delete(key) },
  keys: async () => [...values.keys()],
};
globalThis[Symbol.for('h34s.nitro')] = Object.freeze({
  version: 1, development: false, debug: false,
  runtimeConfig: () => ({}),
  storage: base => base === 'kv' ? kv : undefined,
  onClose: () => () => {},
});

const app = createApp();
const router = createRouter({ preemptive: true });
for (const route of ${JSON.stringify(routes)}) router.use('/' + route, linked[route]);
app.use(router);
const server = createServer(toNodeListener(app));
server.listen(0, '127.0.0.1', async () => {
  try {
    const base = 'http://127.0.0.1:' + server.address().port;
    for (const [method, path, body, status, check] of [${requests.join(',\n      ')}]) {
      const response = await fetch(base + path, {
        method, body, signal: AbortSignal.timeout(10000),
        headers: body === undefined ? {} : { 'content-type': 'application/json' },
      });
      const text = await response.text();
      assert.equal(response.status, status, method + ' ' + path + ': ' + text);
      assert.ok(!text.includes('internal detail') && !text.includes('debug'), 'No diagnostics in a production response');
      check(JSON.parse(text));
    }
    console.log('${consumer.name} published consumer served real h3 requests');
  } catch (error) {
    console.error(error);
    process.exitCode = 1;
  } finally { server.closeAllConnections(); server.close(); }
});
`
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
  // The pinned h3 fixture (with integrity hashes) that the Scala suites use. npm and the
  // JavaScript smoke checks never see the GitHub credentials; only the sbt builds that resolve
  // published artifacts need them.
  for (const file of ['package.json', 'package-lock.json'])
    copyFileSync(resolve(repository, 'scala-modules/exts/h3/test-js', file), join(temporary, file))
  run(
    'npm',
    ['ci', '--ignore-scripts', '--no-audit', '--no-fund'],
    temporary,
    withoutCredentials,
  )

  for (const consumer of consumers) {
    const directory = join(temporary, consumer.name)
    write(
      join(directory, '.jvmopts'),
      readFileSync(resolve(repository, '.jvmopts'), 'utf8'),
    )
    write(join(directory, 'project/build.properties'), 'sbt.version=1.11.7\n')
    write(
      join(directory, 'project/plugins.sbt'),
      'addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")\n',
    )
    const libraries = [
      ...consumer.libraries.map(library => `"dev.yuri4n" %%% "${library}" % "${version}"`),
      ...consumer.extra ?? [],
    ]
    write(join(directory, 'build.sbt'), `
scalaVersion := "${scalaVersion}"
enablePlugins(ScalaJSPlugin)
externalResolvers := {
  require(!sys.props.get("sbt.override.build.repos").exists(_.equalsIgnoreCase("true")), "Fresh consumer checks require their explicit Maven resolvers; disable sbt.override.build.repos in the sbt launcher.")
  Seq(${resolver}, "Maven Central" at "https://repo.maven.apache.org/maven2")
}
${published
  ? 'credentials ++= (for { actor <- sys.env.get("GITHUB_ACTOR").toSeq; token <- sys.env.get("GITHUB_TOKEN").toSeq } yield Credentials("GitHub Package Registry", "maven.pkg.github.com", actor, token))'
  : ''}
libraryDependencies ++= Seq(
  ${libraries.join(',\n  ')},
)
scalaJSLinkerConfig ~= (_.withModuleKind(org.scalajs.linker.interface.ModuleKind.CommonJSModule).withOutputPatterns(org.scalajs.linker.interface.OutputPatterns.fromJSFile("%s.cjs")))
Compile / fastLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "out"
TaskKey[Unit]("writeDependencies") := IO.write(baseDirectory.value / "dependencies.txt", (Compile / update).value.allModules.map(m => m.organization + ":" + m.name + ":" + m.revision).sorted.mkString("\\n"))
TaskKey[Unit]("writeArtifactPaths") := IO.write(baseDirectory.value / "artifacts.txt", (Compile / update).value.allFiles.map(_.getAbsolutePath).sorted.mkString("\\n"))
`)
    write(
      join(directory, 'src/main/scala/Consumer.scala'),
      `import scala.scalajs.js.annotation.JSExportTopLevel\n${consumer.source}`,
    )
    run('sbt', ['compile', 'writeDependencies', 'writeArtifactPaths', 'fastLinkJS'], directory)
    const dependencies = readFileSync(join(directory, 'dependencies.txt'), 'utf8').split('\n')
    const artifacts = readFileSync(join(directory, 'artifacts.txt'), 'utf8')
    assert(!/[/\\]local[/\\]dev\.yuri4n[/\\]/.test(artifacts), 'Consumer resolved a local Ivy h34s artifact')
    if (!published)
      assert(artifacts.includes(maven), 'Consumer did not use the isolated Maven staging repository')
    for (const library of consumer.libraries) {
      assert(
        dependencies.includes(`dev.yuri4n:${library}_sjs1_3:${version}`),
        `${consumer.name} consumer did not resolve ${library} ${version}`,
      )
    }
    const unexpected = dependencies.filter(dependency =>
      consumer.forbidden.some(prefix => dependency.startsWith(prefix)))
    assert.deepEqual(unexpected, [], `${consumer.name} consumer resolved dependencies it did not ask for`)
    assert(
      !dependencies.some(dependency => /^org\.scalablytyped(?:\.|:)/.test(dependency)),
      'Consumer acquired generated local-only facade artifacts',
    )
    write(join(directory, 'smoke.cjs'), smoke(consumer))
    run(process.execPath, ['smoke.cjs'], directory, withoutCredentials)
  }
  console.log(`Fresh Maven consumers passed for ${version}: ${consumers.map(consumer => consumer.name).join(', ')}`)
}
finally {
  rmSync(temporary, { recursive: true, force: true })
}
