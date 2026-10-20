import assert from 'node:assert/strict'
import { execFileSync, spawn } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, renameSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs' // claude 2026-10-19: changed: readdirSync and statSync for the prerender bundle check
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { dirname, join, relative, resolve, sep } from 'node:path' // claude 2026-10-19: changed: relative and sep for the prerender bundle check
import process from 'node:process'
import { setTimeout as delay } from 'node:timers/promises'
import { fileURLToPath, pathToFileURL } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const temporary = mkdtempSync(join(tmpdir(), 'h34s-packed-nuxt-'))
const app = join(temporary, 'independent app')
const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'
const children = new Set()

function write(path, content) {
  mkdirSync(dirname(path), { recursive: true })
  writeFileSync(path, content)
}

function start(args, env = {}) {
  const child = spawn(process.execPath, args, {
    cwd: app,
    detached: process.platform !== 'win32',
    stdio: ['ignore', 'pipe', 'pipe'],
    env: {
      ...process.env,
      NUXT_TELEMETRY_DISABLED: '1',
      NODE_OPTIONS: `${process.env.NODE_OPTIONS ?? ''} --enable-source-maps`,
      ...env,
    },
  })
  const running = { child, log: '', result: null }
  children.add(running)
  for (const stream of [child.stdout, child.stderr]) {
    stream.on('data', (data) => {
      running.log = (running.log + data).slice(-32_000)
    })
  }
  running.done = new Promise((resolve) => {
    child.once('error', (error) => {
      running.result = { error }
      resolve(running.result)
    })
    child.once('close', (code, signal) => {
      running.result = { code, signal }
      resolve(running.result)
    })
  })
  return running
}

async function stop(running) {
  if (!running || !children.has(running))
    return
  function signal(name) {
    try {
      if (process.platform === 'win32')
        running.child.kill(name)
      else
        process.kill(-running.child.pid, name)
    }
    catch (error) {
      if (error.code !== 'ESRCH')
        throw error
    }
  }
  // The Nuxt CLI can own child processes; stop its whole isolated process group.
  signal('SIGTERM')
  const stopped = await Promise.race([running.done.then(() => true), delay(5_000, false, { ref: false })])
  if (!stopped) {
    signal('SIGKILL')
    await running.done
  }
  children.delete(running)
}

async function port() {
  const server = createServer()
  await new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', resolve)
  })
  const port = server.address().port
  await new Promise(resolve => server.close(resolve))
  return port
}

async function response(url, running, revision) {
  const deadline = Date.now() + 90_000
  let last = 'no response'
  while (Date.now() < deadline) {
    if (running.result)
      throw new Error(`Nuxt exited before serving ${revision}: ${JSON.stringify(running.result)}\n${running.log}`)
    try {
      const result = await fetch(url, { signal: AbortSignal.timeout(2_000) })
      const body = await result.text()
      last = `${result.status}: ${body}`
      if (result.ok) {
        const value = JSON.parse(body)
        if (value.revision === revision)
          return value
      }
    }
    catch (error) {
      last = error.message
    }
    await delay(250)
  }
  throw new Error(`Nuxt did not serve ${revision}: ${last}\n${running.log}`)
}

/** The h34s records in one probe response: debug flag, runtime config, storage and close hooks. */
function assertRecords(value, { debug }) {
  assert.equal(value.debug, debug, 'Request record debug flag')
  assert.equal(value.startupDebug, debug, 'Startup record debug flag')
  assert.equal(value.configDebug, debug, 'runtimeConfig.h34s.debug through the request record')
  assert.equal(value.startupConfigDebug, debug, 'runtimeConfig.h34s.debug through the startup record')
  assert.deepEqual(value.stored, { revision: value.revision }, 'Request record storage')
  assert.deepEqual(value.startupStored, { revision: value.revision }, 'Startup record storage is the same Nitro storage')
  assert.deepEqual(value.keys, ['probe'], 'Storage keys are relative to the mount')
  assert.equal(value.deleted, true, 'Storage deletes')
  assert.equal(value.onClose, 'function', 'Startup record registers close hooks')
}

/** A linked route module: its default export carries the record that `EventHandler.at` writes. */
function routeModule(file, route, method, revision) {
  write(join(app, 'server/.scala', file), `const handler = event => ({ data: { message: ${JSON.stringify(`${method} ${revision}`)} }, linkedUrl: import.meta.url, method: event.method })
handler.__is_handler__ = true
Object.defineProperty(handler, Symbol.for('h34s.handler'), {
  value: { version: 1, route: ${JSON.stringify(route)}, method: ${JSON.stringify(method)}, types: () => ({ success: '{ data: { message: string } }', errors: '{ errors: Array<{ message: string }> }' }) },
})
export default handler
`)
}

/** What \`h34s-scala dev\` does after linking: the packed package writes manifest and declarations. */
function collectRoutes() {
  execFileSync(process.execPath, ['--input-type=module', '-e', `
import { loadConfig, writeRoutes } from '@yuri4n/h34s-nuxt/build'
await writeRoutes({ root: process.cwd(), config: await loadConfig() })
`], { cwd: app, stdio: 'inherit' })
}

async function routed(url, running, init, expected) {
  const deadline = Date.now() + 90_000
  let last = 'no response'
  while (Date.now() < deadline) {
    if (running.result)
      throw new Error(`Nuxt exited before serving ${url}: ${JSON.stringify(running.result)}\n${running.log}`)
    try {
      const result = await fetch(url, { ...init, signal: AbortSignal.timeout(2_000) })
      last = `${result.status}: ${await result.text()}`
      if (result.ok && JSON.parse(last.slice(last.indexOf(' ') + 1)).data?.message === expected)
        return JSON.parse(last.slice(last.indexOf(' ') + 1))
    }
    catch (error) {
      last = error.message
    }
    await delay(250)
  }
  throw new Error(`Nuxt did not serve ${expected} at ${url}: ${last}\n${running.log}`)
}

// claude 2026-10-19: changed: also exports `greeting`, an h3 handler that a route file mounts, the other way to mount a Scala.js handler
function linkedModule(revision) {
  const output = join(app, 'server/.scala/hello.js')
  write(`${output}.next`, `export const revision = ${JSON.stringify(revision)}\nexport function probe() { return new Error('h34s source map probe').stack }\nexport const linkedUrl = import.meta.url\nexport function greeting() { return { greeting: 'linked' } }\ngreeting.__is_handler__ = true\n//# sourceMappingURL=hello.js.map\n`)
  renameSync(`${output}.next`, output)
}

// claude 2026-10-19: created: the text of every file under a directory, without its node_modules
function texts(directory) {
  return readdirSync(directory, { recursive: true, withFileTypes: true })
    .map(entry => join(entry.parentPath, entry.name))
    .filter(file => !relative(directory, file).split(sep).includes('node_modules'))
    .filter(file => statSync(file).isFile())
    .map(file => readFileSync(file, 'utf8'))
}

// claude 2026-10-19: created: Nitro's prerender bundle ({{ buildDir }}/prerender of the nitro-prerender preset)
function prerenderBundles() {
  return [join(app, '.nuxt/prerender'), join(app, 'node_modules/.cache/nuxt/.nuxt/prerender')]
}

// claude 2026-10-19: created: runs one `nuxt build` with the given environment and waits for it
async function nuxtBuild(nuxt, env = {}) {
  const build = start([nuxt, 'build'], { NODE_ENV: 'production', ...env })
  const result = await Promise.race([build.done, delay(120_000, null, { ref: false })])
  if (result)
    children.delete(build)
  return { build, result }
}

try {
  const nuxtPackage = process.env.H34S_NUXT_PACKAGE
    ? resolve(process.env.H34S_NUXT_PACKAGE)
    : join(root, 'node_modules/nuxt')
  assert.ok(existsSync(join(nuxtPackage, 'package.json')), 'Install the repository dependencies first, or set H34S_NUXT_PACKAGE to an installed Nuxt package directory')
  const nuxtVersion = JSON.parse(readFileSync(join(nuxtPackage, 'package.json'), 'utf8')).version
  const [packed] = JSON.parse(execFileSync(npm, [
    'pack', './packages/nuxt', '--ignore-scripts', '--json',
    '--cache', join(temporary, 'pack-cache'), '--pack-destination', temporary,
  ], { cwd: root, encoding: 'utf8' }))
  write(join(app, 'package.json'), '{"name":"packed-nuxt-consumer","private":true,"type":"module"}\n')
  execFileSync(npm, [
    'install', '--offline', '--ignore-scripts', '--omit=optional', '--legacy-peer-deps',
    '--no-audit', '--no-fund', '--cache', join(temporary, 'install-cache'),
    join(temporary, packed.filename),
  ], { cwd: app, encoding: 'utf8' })
  // Only Nuxt is reused from the installed toolchain. h34s must be real packed
  // files under node_modules, where Nitro's externalization behavior differs.
  symlinkSync(nuxtPackage, join(app, 'node_modules/nuxt'), 'dir')
  // claude 2026-10-19: changed: scalaPrerender: 'stub' with one prerendered route; H34S_CHECK_PRERENDER_SCALA adds a route that calls Scala.js, and 'direct' also prerenders the Scala.js route itself (review F1, F2)
  write(join(app, 'nuxt.config.mjs'), `const scalaRoutes = { true: ['/calls-scala.txt'], direct: ['/calls-scala.txt', '/api/greeting'] }
export default {
  modules: [['@yuri4n/h34s-nuxt', { scalaPrerender: 'stub' }]],
  devtools: { enabled: false },
  telemetry: false,
  compatibilityDate: '2025-05-15',
  nitro: {
    preset: 'node-server',
    prerender: {
      routes: ['/prerendered.txt', ...(scalaRoutes[process.env.H34S_CHECK_PRERENDER_SCALA] ?? [])]
    }
  }
}\n`)
  write(join(app, 'h34s.config.mjs'), `import { defineConfig } from '@yuri4n/h34s-nuxt/build'
export default defineConfig({ modules: [{ output: 'server/.scala', entries: ['hello.js'] }] })\n`)
  write(join(app, 'app/app.vue'), '<template><main>Packed h34s Nuxt consumer</main></template>\n')
  write(join(app, 'src/main/scala/Hello.scala'), 'object Hello:\n  def probe = new RuntimeException("h34s source map probe")\n')
  write(join(app, 'server/.scala/hello.js.map'), JSON.stringify({
    version: 3, file: 'hello.js', names: [],
    sources: [pathToFileURL(join(app, 'src/main/scala/Hello.scala')).href],
    mappings: 'AAAA;AACA',
  }))
  linkedModule('first')
  // A Scala route without a route file: the manifest mounts it, and its declaration types $fetch.
  routeModule('greeting-get.js', '/api/greeting', 'get', 'first')
  collectRoutes()
  assert.match(readFileSync(join(app, 'server/.scala/greeting-get.d.ts'), 'utf8'), /export type SuccessBody = \{ data: \{ message: string \} \}/)
  // The probe reads the h34s Nitro records the way the Scala.js handlers do: the request record
  // (development, debug, runtime config, storage) and the startup record (runtime config, storage,
  // close hooks). Storage and runtime config come from the plugin's `nitropack/runtime` import.
  write(join(app, 'server/api/probe.get.ts'), `import { revision, probe, linkedUrl } from '../.scala/hello.js'
export default defineEventHandler(async (event) => {
  const request = event.context.h34s
  const startup = (globalThis as any)[Symbol.for('h34s.nitro')]
  // A named mount, as KHubKv.mount('kv') opens one, with the methods the KV adapters call.
  const storage = request.storage('h34s')
  await storage.set('probe', { revision })
  const stored = await storage.get('probe')
  const startupStored = await startup.storage('h34s').get('probe')
  const keys = await storage.keys()
  await storage.del('probe')
  return {
    development: request.development,
    debug: request.debug,
    startupDebug: startup.debug,
    configDebug: request.runtimeConfig().h34s?.debug,
    startupConfigDebug: startup.runtimeConfig().h34s?.debug,
    stored, startupStored, keys,
    deleted: !(await storage.has('probe')),
    onClose: typeof startup.onClose,
    revision, linkedUrl, stack: probe()
  }
})\n`)
  // claude 2026-10-19: created: a Scala.js handler mounted by a route file at GET /api/linked, next to the manifest route GET /api/greeting
  write(join(app, 'server/api/linked.get.ts'), `export { greeting as default } from '../.scala/hello.js'\n`)
  // claude 2026-10-19: created: a prerendered route without Scala.js, and a prerendered route that calls both Scala routes and catches each error, as useFetch does
  write(join(app, 'server/routes/prerendered.txt.ts'), `export default defineEventHandler((event) => {
  setResponseHeader(event, 'content-type', 'text/plain; charset=utf-8')
  return 'Prerendered without Scala.js\\n'
})\n`)
  write(join(app, 'server/routes/calls-scala.txt.ts'), `export default defineEventHandler(async (event) => {
  setResponseHeader(event, 'content-type', 'text/plain; charset=utf-8')
  const answered = (route: string) => event.$fetch(route).then(() => 200, (error: any) => error?.statusCode)
  return \`The Scala.js routes answered \${await answered('/api/greeting')} and \${await answered('/api/linked')}\\n\`
})\n`)
  const nuxt = join(app, 'node_modules/nuxt/bin/nuxt.mjs')
  const devPort = await port()
  const dev = start([nuxt, 'dev', '--host', '127.0.0.1', '--port', String(devPort)], { NODE_ENV: 'development' })
  const devUrl = `http://127.0.0.1:${devPort}/api/probe`
  const first = await response(devUrl, dev, 'first')
  assert.equal(first.development, true, 'The installed runtime plugin must receive Nuxt development mode')
  assertRecords(first, { debug: false })
  assert.equal(first.linkedUrl, pathToFileURL(join(app, 'server/.scala/hello.js')).href, 'Development loads the linked module directly')
  assert.match(first.stack, /Hello\.scala:2/, 'Node maps the linked JavaScript stack back to Scala')
  const greetingUrl = `http://127.0.0.1:${devPort}/api/greeting`
  const greeting = await routed(greetingUrl, dev, {}, 'get first')
  assert.equal(greeting.linkedUrl, pathToFileURL(join(app, 'server/.scala/greeting-get.js')).href, 'Development loads the Scala route module directly')
  const routeTypes = readFileSync(join(app, '.nuxt/types/nitro-routes.d.ts'), 'utf8')
  assert.match(routeTypes, /'\/api\/greeting': \{\s*'get': Simplify<Serialize<Awaited<ReturnType<typeof import\('\.\.\/\.\.\/server\/\.scala\/greeting-get'\)\.default>>>>/, 'Nitro types the manifest route from its declaration')
  linkedModule('second')
  const second = await response(devUrl, dev, 'second')
  assert.equal(second.development, true)
  assert.match(second.stack, /Hello\.scala:2/)
  // A new Scala route while Nuxt runs: the next collection mounts it without a restart.
  routeModule('greeting-post.js', '/api/greeting', 'post', 'second')
  collectRoutes()
  await routed(greetingUrl, dev, { method: 'POST' }, 'post second')
  assert.match(readFileSync(join(app, '.nuxt/types/nitro-routes.d.ts'), 'utf8'), /greeting-post/, 'The reload writes the new route type')
  await stop(dev)

  // claude 2026-10-19: created: with scalaPrerender: 'stub', a prerendered page that reaches a Scala.js route stops the build (review F1)
  const stubbed = 'Scala.js is not bundled for prerendering'
  const linked = 'h34s source map probe'
  const routeRecord = 'h34s.handler' // claude 2026-10-19: created: the record key in every linked route module that the module mounts from the manifest
  const failing = await nuxtBuild(nuxt, { H34S_CHECK_PRERENDER_SCALA: 'true' })
  assert.ok(failing.result && Number.isInteger(failing.result.code) && failing.result.code !== 0, `A prerendered page that calls a Scala.js route must stop the build with scalaPrerender: 'stub': ${JSON.stringify(failing.result)}\n${failing.build.log}`)
  assert.match(failing.build.log, /\[h34s\] GET \/api\/greeting answered 501 while Nuxt prerendered \/calls-scala\.txt/, failing.build.log)
  assert.match(failing.build.log, /scalaPrerender is 'stub'[\s\S]*GET \/api\/greeting, while Nuxt prerendered \/calls-scala\.txt/, failing.build.log)
  // claude 2026-10-19: created: the stub also answers the handler that a route file mounts
  assert.match(failing.build.log, /\[h34s\] GET \/api\/linked answered 501 while Nuxt prerendered \/calls-scala\.txt/, failing.build.log)
  assert.match(failing.build.log, /scalaPrerender is 'stub'[\s\S]*GET \/api\/linked, while Nuxt prerendered \/calls-scala\.txt/, failing.build.log)
  // claude 2026-10-19: created: h3 and Nitro handle the stub's 501, so the build log has no unhandled-error block (review F2)
  assert.doesNotMatch(failing.build.log, /\[request error\]|\[unhandled\]/, failing.build.log)

  // claude 2026-10-19: created: a directly prerendered Scala.js route fails in Nitro; h34s logs its list and lets Nitro stop the build with its own summary (review F1, F2)
  const direct = await nuxtBuild(nuxt, { H34S_CHECK_PRERENDER_SCALA: 'direct' })
  assert.ok(direct.result && Number.isInteger(direct.result.code) && direct.result.code !== 0, `A directly prerendered Scala.js route must fail with scalaPrerender: 'stub': ${JSON.stringify(direct.result)}\n${direct.build.log}`)
  assert.match(direct.build.log, /\[h34s\] GET \/api\/greeting answered 501 while Nuxt prerendered \/api\/greeting:/, direct.build.log)
  assert.match(direct.build.log, /scalaPrerender is 'stub'[\s\S]*GET \/api\/greeting, while Nuxt prerendered \/api\/greeting\n[\s\S]*GET \/api\/greeting, while Nuxt prerendered \/calls-scala\.txt/, direct.build.log)
  assert.match(direct.build.log, /Errors prerendering:[\s\S]*\/api\/greeting[\s\S]*Exiting due to prerender errors/, direct.build.log)
  assert.doesNotMatch(direct.build.log, /\[request error\]|\[unhandled\]/, direct.build.log)

  // claude 2026-10-19: changed: the production build prerenders /prerendered.txt with scalaPrerender: 'stub' (review F2)
  for (const directory of prerenderBundles())
    rmSync(directory, { recursive: true, force: true })
  const { build, result } = await nuxtBuild(nuxt)
  assert.ok(result && result.code === 0, `Nuxt production build failed or timed out: ${JSON.stringify(result)}\n${build.log}`)
  // claude 2026-10-19: created: the prerender bundle has the stub and no linked code; the server output has the linked code and no stub (review F2)
  assert.ok(!build.log.includes('[h34s]'), `No prerendered request may reach the stub:\n${build.log}`)
  assert.equal(readFileSync(join(app, '.output/public/prerendered.txt'), 'utf8'), 'Prerendered without Scala.js\n')
  const bundles = prerenderBundles().filter(directory => existsSync(directory))
  assert.equal(bundles.length, 1, `Expected one Nitro prerender bundle in ${prerenderBundles().join(' or ')}`)
  const prerendered = texts(bundles[0])
  assert.ok(prerendered.some(text => text.includes(stubbed)), 'The prerender bundle mounts the stub')
  assert.ok(!prerendered.some(text => text.includes(linked)), 'The prerender bundle has no linked Scala.js')
  assert.ok(!prerendered.some(text => text.includes(routeRecord)), 'The prerender bundle has no linked Scala route module') // claude 2026-10-19: created: the mounted routes import their linked files by absolute path
  const served = texts(join(app, '.output/server'))
  assert.ok(served.some(text => text.includes(linked)), 'The server output bundles the linked Scala.js')
  assert.ok(served.some(text => text.includes(routeRecord)), 'The server output bundles the linked Scala route modules') // claude 2026-10-19: created: the counterpart of the prerender bundle check
  assert.ok(!served.some(text => text.includes(stubbed)), 'The server output has no stub')
  // A deployed application must not need the original Scala.js output tree.
  rmSync(join(app, 'server/.scala'), { recursive: true, force: true })
  const productionPort = await port()
  const production = start([join(app, '.output/server/index.mjs')], {
    NODE_ENV: 'production', NITRO_HOST: '127.0.0.1', NITRO_PORT: String(productionPort),
  })
  const deployed = await response(`http://127.0.0.1:${productionPort}/api/probe`, production, 'second')
  assert.equal(deployed.development, false, 'Production disables development diagnostics')
  assert.ok(!deployed.linkedUrl.includes('/server/.scala/'), 'Production bundles the generated module')
  const bundled = await routed(`http://127.0.0.1:${productionPort}/api/greeting`, production, { method: 'POST' }, 'post second')
  assert.ok(!bundled.linkedUrl.includes('/server/.scala/'), 'Production bundles the Scala route module')
  assertRecords(deployed, { debug: false })
  // claude 2026-10-19: created: the Scala route that the prerender stub answered runs the linked code in the server output
  const bundledGet = await routed(`http://127.0.0.1:${productionPort}/api/greeting`, production, {}, 'get first')
  assert.ok(!bundledGet.linkedUrl.includes('/server/.scala/'), 'Production bundles the Scala route module that the stub replaced for prerendering')
  // claude 2026-10-19: created: the handler mounted by a route file runs the linked code in the server output
  const linkedRoute = await fetch(`http://127.0.0.1:${productionPort}/api/linked`, { signal: AbortSignal.timeout(5_000) })
  assert.equal(linkedRoute.status, 200)
  assert.deepEqual(await linkedRoute.json(), { greeting: 'linked' })
  await stop(production)

  // NUXT_H34S_DEBUG overrides the declared runtimeConfig.h34s.debug at runtime, without a rebuild.
  const debugPort = await port()
  const debugging = start([join(app, '.output/server/index.mjs')], {
    NODE_ENV: 'production', NITRO_HOST: '127.0.0.1', NITRO_PORT: String(debugPort), NUXT_H34S_DEBUG: 'true',
  })
  const debugged = await response(`http://127.0.0.1:${debugPort}/api/probe`, debugging, 'second')
  assert.equal(debugged.development, false, 'The debug flag does not switch on development mode')
  assertRecords(debugged, { debug: true })
  console.log(`Verified packed h34s with Nuxt ${nuxtVersion}: dev diagnostics, direct loading, Scala source maps, relink reload, Scala routes from the manifest (typed, added while running), scalaPrerender: 'stub' (manifest and route-file mounts, prerender bundle, server output, build error, Nitro's own prerender error), production bundling, production diagnostics disabled, NUXT_H34S_DEBUG, runtime config and storage from nitropack/runtime`) // claude 2026-10-19: changed: names the scalaPrerender checks, including Nitro's own prerender error and both mount forms
}
finally {
  await Promise.allSettled([...children].map(stop))
  rmSync(temporary, { recursive: true, force: true })
}
