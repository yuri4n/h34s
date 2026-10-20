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
import { join } from 'node:path'
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
  assert.deepEqual(
    await loadConfig({ root: f.root, configFile: 'custom.mjs' }),
    f.config,
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
