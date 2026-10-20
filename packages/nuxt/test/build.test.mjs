import assert from 'node:assert/strict'
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path' // claude 2026-10-20: changed: dirname for the config files of the sbtRoot tests
// The package uses Node's runner without additional test dependencies.
// eslint-disable-next-line test/no-import-node-test
import test from 'node:test'
import { EventEmitter } from 'node:events'
import { setTimeout as delay } from 'node:timers/promises'
import {
  buildScala,
  defineConfig,
  linkScala,
  loadConfig,
  watchScala,
} from '../src/build.mjs'

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'h34s-linker-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  writeFileSync(join(root, 'build.sbt'), '') // claude 2026-10-20: created: the app root is the sbt build, as sbtRoot's default needs (ADR-0009)
  const config = {
    modules: [{ output: 'server/.scala', entries: ['hello.js'] }],
  }
  function output(text = 'export const hello = 42') {
    mkdirSync(join(root, 'server/.scala'), { recursive: true })
    writeFileSync(join(root, 'server/.scala/hello.js'), text)
  }
  return { root, config, output }
}

test('links an app-owned root project, removes stale chunks and checks its named entry', (t) => {
  const f = fixture(t)
  f.output('stale')
  writeFileSync(join(f.root, 'server/.scala/old.js'), 'old')
  buildScala({
    ...f,
    run(command, args, options) {
      assert.ok(['sbt', 'sbt.bat'].includes(command))
      assert.deepEqual(args, ['-Dsbt.supershell=false', '-batch', 'fullLinkJS'])
      assert.equal(options.cwd, f.root)
      assert.equal(existsSync(join(f.root, 'server/.scala')), false)
      f.output()
      return { status: 0 }
    },
  })
  assert.equal(existsSync(join(f.root, 'server/.scala/old.js')), false)
})

test('rejects malformed or overlapping paths before running sbt or deleting any files', (t) => {
  const f = fixture(t)
  f.output('preserved')
  const invalid = [
    {},
    { modules: [] },
    { modules: [null] },
    ...[
      '.',
      '..',
      '/tmp',
      '../outside',
      'a/../b',
      'a\\b',
      '.git',
      'node_modules/scala',
      'C:/src',
      'server/D:escape',
    ].map(output => ({ modules: [{ output, entries: ['main.js'] }] })),
    ...['../private.js', '/tmp/private.js', 'main.ts', ''].map(entry => ({
      modules: [{ output: 'server/.scala', entries: [entry] }],
    })),
    { modules: [{ output: 'server/.scala', entries: [] }] },
    { modules: [{ ...f.config.modules[0], project: 'foo;clean' }] },
    {
      modules: [
        ...f.config.modules,
        { output: 'server/.scala/child', entries: ['main.js'] },
      ],
    },
  ]
  for (const config of invalid) {
    assert.throws(() =>
      buildScala({
        root: f.root,
        config,
        run: () => assert.fail('must not start sbt'),
      }),
    )
    assert.ok(existsSync(join(f.root, 'server/.scala/hello.js')))
  }
})

test('refuses symlinked output ancestors before removing any configured output', (t) => {
  const f = fixture(t)
  const outside = fixture(t)
  f.output()
  outside.output('preserved outside')
  symlinkSync(join(outside.root, 'server'), join(f.root, 'alias'), 'dir')
  const config = {
    modules: [
      ...f.config.modules,
      { output: 'alias/.scala', entries: ['hello.js'] },
    ],
  }
  assert.throws(() => buildScala({ root: f.root, config }), /real directories/)
  assert.ok(existsSync(join(outside.root, 'server/.scala/hello.js')))
  assert.ok(existsSync(join(f.root, 'server/.scala/hello.js')))
})

test('rejects entries that resolve outside the configured output', (t) => {
  const f = fixture(t)
  const outside = fixture(t)
  outside.output()
  assert.throws(
    () =>
      buildScala({
        ...f,
        run() {
          mkdirSync(join(f.root, 'server/.scala'), { recursive: true })
          symlinkSync(
            join(outside.root, 'server/.scala/hello.js'),
            join(f.root, 'server/.scala/hello.js'),
          )
          return { status: 0 }
        },
      }),
    /did not produce/,
  )
})

test('dev preserves existing chunks while watch repeats all configured projects together', (t) => {
  const f = fixture(t)
  f.output()
  for (const mode of ['dev', 'watch']) {
    linkScala({
      ...f,
      mode,
      run(_command, args) {
        assert.ok(existsSync(join(f.root, 'server/.scala/hello.js')))
        assert.equal(
          args.at(-1),
          mode === 'watch' ? '~;fastLinkJS' : 'fastLinkJS',
        )
        return { status: 0 }
      },
    })
  }
  const config = defineConfig({
    modules: [
      { project: 'zioApp', output: 'generated/zio', entries: ['hello.js'] },
      { project: 'kyoApp', output: 'generated/kyo', entries: ['hello.js'] },
    ],
  })
  linkScala({
    root: f.root,
    config,
    mode: 'watch',
    run(_command, args) {
      assert.equal(args.at(-1), '~;zioApp/fastLinkJS;kyoApp/fastLinkJS')
      for (const module of config.modules) {
        mkdirSync(join(f.root, module.output), { recursive: true })
        writeFileSync(join(f.root, module.output, 'hello.js'), 'fresh')
      }
      return { status: 0 }
    },
  })
})

test('loads explicit configuration relative to the app root and explains missing files', async (t) => {
  const f = fixture(t)
  writeFileSync(
    join(f.root, 'custom.mjs'),
    `export default ${JSON.stringify(f.config)}`,
  )
  // claude 2026-10-20: changed: loadConfig also gives the absolute sbtRoot, by default the config file's directory (ADR-0009)
  assert.deepEqual(
    await loadConfig({ root: f.root, configFile: 'custom.mjs' }),
    { ...f.config, sbtRoot: f.root },
  )
  await assert.rejects(
    loadConfig({ root: f.root }),
    /Missing h34s config:.*h34s.config.mjs/,
  )
})

test('entries are optional: the route collection then checks the output', () => {
  const config = { modules: [{ project: 'server', output: 'server/.scala' }] }
  assert.equal(defineConfig(config), config)
})

test('watch keeps sbt linking and collects routes once the linker has written its files', async (t) => {
  const f = fixture(t)
  const config = { modules: [{ project: 'server', output: 'server/.scala' }] }
  const child = new EventEmitter()
  const collected = []
  const watching = watchScala({
    root: f.root,
    config,
    quiet: 50,
    log: { info() {}, error: message => collected.push(message) },
    start(command, args, options) {
      assert.ok(['sbt', 'sbt.bat'].includes(command))
      assert.deepEqual(args, ['-Dsbt.supershell=false', '-batch', '~;server/fastLinkJS'])
      assert.equal(options.cwd, f.root)
      return child
    },
    async collect(options) {
      assert.equal(options.config, config)
      collected.push('collected')
    },
  })
  await delay(50)
  // The linker writes several modules; one collection follows. Declarations do not trigger one.
  writeFileSync(join(f.root, 'server/.scala/a.js'), 'a')
  writeFileSync(join(f.root, 'server/.scala/b.js'), 'b')
  writeFileSync(join(f.root, 'server/.scala/a.d.ts'), 'types')
  await delay(400)
  assert.deepEqual(collected, ['collected'])
  child.emit('exit', 0, null)
  await watching
})

// claude 2026-10-20: created: layout C, a Nuxt root next to a sibling sbt build (ADR-0009)
test('layout C: sbt runs in the sibling build named by sbtRoot, and the outputs stay under the Nuxt root', async (t) => {
  const repository = mkdtempSync(join(tmpdir(), 'h34s-layout-c-'))
  t.after(() => rmSync(repository, { recursive: true, force: true }))
  const web = join(repository, 'web')
  const scala = join(repository, 'scala-server')
  mkdirSync(web)
  mkdirSync(scala)
  writeFileSync(join(scala, 'build.sbt'), '')
  writeFileSync(
    join(web, 'h34s.config.mjs'),
    `export default { sbtRoot: '../scala-server', modules: [{ project: 'server', output: 'server/.scala', entries: ['hello.js'] }] }`,
  )
  const config = await loadConfig({ root: web })
  for (const mode of ['build', 'dev']) {
    linkScala({
      root: web,
      config,
      mode,
      run(_command, args, options) {
        assert.equal(options.cwd, scala, 'sbt runs in the sibling build')
        assert.equal(args.at(-1), mode === 'build' ? 'server/fullLinkJS' : 'server/fastLinkJS')
        // The sibling build's linker writes into the Nuxt root, as its scalaJSLinkerOutputDirectory says.
        mkdirSync(join(web, 'server/.scala'), { recursive: true })
        writeFileSync(join(web, 'server/.scala/hello.js'), 'export const hello = 42')
        return { status: 0 }
      },
    })
  }
  assert.ok(existsSync(join(web, 'server/.scala/hello.js')), 'the output is under the Nuxt root')
  assert.equal(existsSync(join(scala, 'server')), false, 'nothing is written into the sbt build')
})

// claude 2026-10-20: created: a repository with a Nuxt app in web/ and config files written as text (ADR-0009)
function repository(t) {
  const root = mkdtempSync(join(tmpdir(), 'h34s-sbt-root-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const web = join(root, 'web')
  mkdirSync(web)
  function config(sbtRoot, file = 'h34s.config.mjs') {
    mkdirSync(dirname(join(web, file)), { recursive: true })
    writeFileSync(
      join(web, file),
      `export default ${JSON.stringify({ ...(sbtRoot === undefined ? {} : { sbtRoot }), modules: [{ output: 'server/.scala', entries: ['hello.js'] }] })}`,
    )
  }
  return { root, web, config }
}

// claude 2026-10-20: created: sbtRoot resolution (ADR-0009)
test('loadConfig resolves sbtRoot against the config file\'s directory: default, relative and absolute', async (t) => {
  const r = repository(t)
  r.config(undefined)
  assert.equal((await loadConfig({ root: r.web })).sbtRoot, r.web, 'the default is the config file\'s directory')
  r.config(undefined, 'config/h34s.config.mjs')
  assert.equal(
    (await loadConfig({ root: r.web, configFile: 'config/h34s.config.mjs' })).sbtRoot,
    join(r.web, 'config'),
    'the default follows --config, not --root',
  )
  r.config('..', 'relative.mjs')
  assert.equal((await loadConfig({ root: r.web, configFile: 'relative.mjs' })).sbtRoot, r.root)
  r.config('../scala-server', 'sibling.mjs')
  assert.equal((await loadConfig({ root: r.web, configFile: 'sibling.mjs' })).sbtRoot, join(r.root, 'scala-server'))
  const absolute = join(r.root, 'elsewhere')
  r.config(absolute, 'absolute.mjs')
  assert.equal((await loadConfig({ root: r.web, configFile: 'absolute.mjs' })).sbtRoot, absolute)
  // loadConfig resolves the key once: the result links the same way from any root.
  writeFileSync(join(r.root, 'build.sbt'), '')
  const loaded = await loadConfig({ root: r.web, configFile: 'relative.mjs' })
  linkScala({
    root: r.web,
    config: loaded,
    mode: 'dev',
    run(_command, _args, options) {
      assert.equal(options.cwd, r.root)
      mkdirSync(join(r.web, 'server/.scala'), { recursive: true })
      writeFileSync(join(r.web, 'server/.scala/hello.js'), 'fresh')
      return { status: 0 }
    },
  })
})

// claude 2026-10-20: created: sbtRoot validation (ADR-0009)
test('defineConfig rejects an sbtRoot that is not a nonempty path', () => {
  for (const sbtRoot of ['', 3, null, false, 'a\0b', ['..']]) {
    assert.throws(
      () => defineConfig({ sbtRoot, modules: [{ output: 'server/.scala' }] }),
      /sbtRoot must be a nonempty path to the sbt build, relative to the config file, or omitted/,
      String(sbtRoot),
    )
  }
})

// claude 2026-10-20: created: a missing sbt build stops the CLI before sbt or any removal (ADR-0009)
test('a missing sbtRoot or one without an sbt build fails before sbt starts or any output is removed', async (t) => {
  const r = repository(t)
  mkdirSync(join(r.web, 'server/.scala'), { recursive: true })
  writeFileSync(join(r.web, 'server/.scala/hello.js'), 'preserved')
  writeFileSync(join(r.root, 'not-a-directory'), '')
  const cases = [
    [undefined, r.web, 'sbtRoot is not set, so the sbt build is the config file\'s directory', 'has no build.sbt and no project/ directory'],
    ['..', r.root, 'sbtRoot \'..\' resolves to', 'has no build.sbt and no project/ directory'],
    ['../missing', join(r.root, 'missing'), 'sbtRoot \'../missing\' resolves to', 'does not exist'],
    ['../not-a-directory', join(r.root, 'not-a-directory'), 'sbtRoot \'../not-a-directory\' resolves to', 'is not a directory'],
  ]
  for (const [index, [sbtRoot, directory, key, problem]] of cases.entries()) {
    // One file per case: Node caches an imported config file.
    const file = `case-${index}.mjs`
    r.config(sbtRoot, file)
    const config = await loadConfig({ root: r.web, configFile: file })
    const expected = (error) => {
      for (const part of [key, `${directory}, which ${problem}.`, `Set sbtRoot in ${join(r.web, file)}`])
        assert.ok(error.message.includes(part), `${error.message}\nshould name: ${part}`)
      return true
    }
    for (const mode of ['build', 'dev']) {
      assert.throws(() => linkScala({ root: r.web, config, mode, run: () => assert.fail('must not start sbt') }), expected)
    }
    assert.throws(() => watchScala({ root: r.web, config, start: () => assert.fail('must not start sbt') }), expected)
    assert.ok(existsSync(join(r.web, 'server/.scala/hello.js')), 'the output is not removed')
  }
  // A config object that loadConfig did not read names the app root instead of a file.
  assert.throws(
    () => linkScala({ root: r.web, config: { sbtRoot: '../missing', modules: [{ output: 'server/.scala' }] }, run: () => assert.fail('must not start sbt') }),
    /resolves to .*missing, which does not exist\. Set sbtRoot in the h34s config to the directory of your sbt build, relative to the app root/,
  )
  // A project/ directory alone is an sbt build, as for the sbt launcher.
  mkdirSync(join(r.root, 'project'))
  r.config('..', 'project-only.mjs')
  linkScala({
    root: r.web,
    config: await loadConfig({ root: r.web, configFile: 'project-only.mjs' }),
    mode: 'dev',
    run(_command, _args, options) {
      assert.equal(options.cwd, r.root)
      return { status: 0 }
    },
  })
})

// claude 2026-10-20: created: watch runs sbt in the sibling build (ADR-0009)
test('layout C: watch runs sbt in sbtRoot and watches the outputs under the Nuxt root', async (t) => {
  const r = repository(t)
  const scala = join(r.root, 'scala-server')
  mkdirSync(join(scala, 'project'), { recursive: true })
  r.config('../scala-server')
  const config = await loadConfig({ root: r.web })
  const child = new EventEmitter()
  const collected = []
  const watching = watchScala({
    root: r.web,
    config,
    quiet: 50,
    log: { info() {}, error: message => collected.push(message) },
    start(_command, args, options) {
      assert.deepEqual(args, ['-Dsbt.supershell=false', '-batch', '~;fastLinkJS'])
      assert.equal(options.cwd, scala)
      return child
    },
    async collect(options) {
      assert.equal(options.root, r.web)
      collected.push('collected')
    },
  })
  await delay(50)
  writeFileSync(join(r.web, 'server/.scala/hello.js'), 'linked')
  await delay(300)
  assert.deepEqual(collected, ['collected'])
  assert.equal(existsSync(join(scala, 'server')), false)
  child.emit('exit', 0, null)
  await watching
})
