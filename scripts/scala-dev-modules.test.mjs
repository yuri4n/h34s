import assert from 'node:assert/strict'
import { execFile } from 'node:child_process'
import {
  mkdir,
  mkdtemp,
  readFile,
  rm,
  symlink,
  writeFile,
} from 'node:fs/promises'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
// These build-tool checks run directly in Node without the Nuxt/Vitest runtime.
// eslint-disable-next-line test/no-import-node-test
import test from 'node:test'
import { pathToFileURL } from 'node:url'
import { promisify } from 'node:util'
import { scalaDevModules } from '@yuri4n/h34s-nuxt/rollup'

const run = promisify(execFile)

async function fixture(t) {
  const root = await mkdtemp(join(tmpdir(), 'h34s-dev-modules-'))
  t.after(() => rm(root, { recursive: true, force: true }))
  async function put(path, content = 'export const value = 42\n') {
    const file = join(root, path)
    await mkdir(dirname(file), { recursive: true })
    await writeFile(file, content)
    return file
  }
  await put('package.json', '{"type":"module"}')
  const importer = await put('server/api/handler.ts', '')
  const plugin = scalaDevModules(root)
  const resolveId = (
    source,
    from = importer,
  ) => plugin.resolveId.handler(source, from)
  return { root, put, importer, plugin, resolveId }
}

test('externalizes both linked directories, explicit and extensionless imports, as file URLs', async (
  t,
) => {
  const f = await fixture(t)
  for (const directory of ['.scala', '.scala-kyo']) {
    const file = await f.put(`server/${directory}/hello world.js`)
    const expected = { id: pathToFileURL(file).href, external: true }
    assert.deepEqual(
      await f.resolveId(`../${directory}/hello world.js`),
      expected,
    )
    assert.deepEqual(await f.resolveId(`../${directory}/hello world`), expected)
    assert.deepEqual(await f.resolveId(file), expected)
    assert.deepEqual(await f.resolveId(pathToFileURL(file).href), expected)
    assert.deepEqual(
      await f.resolveId(
        `../${directory}/hello world`,
        pathToFileURL(f.importer).href,
      ),
      expected,
    )
  }
})

test('preserves unrelated, virtual, transformed, missing and non-JavaScript imports', async (
  t,
) => {
  const f = await fixture(t)
  const linked = await f.put('server/.scala/main.js')
  await f.put('server/.scala/main.ts')
  await f.put('server/.scala-other/main.js')
  await f.put('server/ordinary.js')
  await mkdir(join(f.root, 'server/.scala/directory.js'))
  const untouched = [
    'h3',
    'node:fs',
    '\0virtual',
    'https://example.test/main.js',
    '../ordinary.js',
    '../.scala-other/main.js',
    '../.scala/../ordinary.js',
    '../.scala/missing.js',
    '../.scala/directory.js',
    '../.scala/main.ts',
    '../.scala/main.js.map',
    '../.scala/main.js?raw',
    '../.scala/main.js#fragment',
    `${pathToFileURL(linked).href}?raw`,
    `${pathToFileURL(linked).href}#fragment`,
    'file://remote.invalid/main.js',
  ]
  for (const source of untouched) {
    assert.equal(await f.resolveId(source), null, source)
  }
  assert.equal(await f.resolveId('../.scala/main.js', '\0virtual'), null)
  assert.equal(await f.plugin.resolveId.handler('../.scala/main.js'), null)
})

test('supports configured directories and linking after plugin creation', async (
  t,
) => {
  const f = await fixture(t)
  const plugin = scalaDevModules(f.root, ['linked/server'])
  const file = join(f.root, 'linked/server/entry.js')
  assert.equal(await plugin.resolveId.handler(file), null)
  await f.put('linked/server/entry.js')
  assert.deepEqual(
    await plugin.resolveId.handler(file),
    { id: pathToFileURL(file).href, external: true },
  )
  assert.equal(await f.resolveId(file), null)
})

test('rejects output configuration equal to or outside the project root', async (
  t,
) => {
  const f = await fixture(t)
  for (const directory of ['.', '', '..', '../outside', f.root]) {
    assert.throws(
      () => scalaDevModules(f.root, [directory]),
      /inside the project root/,
    )
  }
})

test('does not externalize files or output directories escaping through symlinks', async (
  t,
) => {
  const f = await fixture(t)
  const outside = await fixture(t)
  const externalFile = await outside.put('private.js')
  await mkdir(join(f.root, 'server/.scala'))
  await symlink(externalFile, join(f.root, 'server/.scala/escape.js'))
  await symlink(outside.root, join(f.root, 'server/.scala-kyo'))
  assert.equal(await f.resolveId('../.scala/escape.js'), null)
  assert.equal(await f.resolveId('../.scala-kyo/private.js'), null)

  // An alias inside the same linked directory remains a bounded local module.
  const internal = await f.put('server/.scala/internal.js')
  await symlink(internal, join(f.root, 'server/.scala/alias.js'))
  assert.deepEqual(
    await f.resolveId('../.scala/alias.js'),
    { id: pathToFileURL(internal).href, external: true },
  )
})

test('recognizes canonical import paths when the project root is a symlink', async (
  t,
) => {
  const f = await fixture(t)
  const container = await fixture(t)
  const alias = join(container.root, 'checkout')
  await symlink(f.root, alias)
  const file = await f.put('server/.scala/entry.js')
  const plugin = scalaDevModules(alias)
  const expected = { id: pathToFileURL(file).href, external: true }
  assert.deepEqual(await plugin.resolveId.handler(file), expected)
  assert.deepEqual(
    await plugin.resolveId.handler('../.scala/entry', f.importer),
    expected,
  )
  assert.deepEqual(
    await plugin.resolveId.handler(
      '../.scala/entry',
      join(alias, 'server/api/handler.ts'),
    ),
    expected,
  )
})

test('Rollup leaves linked modules to Node, which loads internal imports and original Scala maps', async (
  t,
) => {
  const f = await fixture(t)
  const scala = await f.put(
    'src/Main.scala',
    'object Main:\n  throw new Exception("native map probe")\n',
  )
  const internalCode = 'export function probe() { throw new Error("native map probe") }\n//# sourceMappingURL=internal.js.map\n'
  const internal = await f.put('server/.scala-kyo/internal.js', internalCode)
  const map = JSON.stringify({
    version: 3,
    sources: [pathToFileURL(scala).href],
    names: [],
    mappings: 'AACA',
  })
  await writeFile(`${internal}.map`, map)
  const linked = await f.put(
    'server/.scala-kyo/entry.js',
    'export { probe } from "./internal.js"\n',
  )
  const entry = await f.put(
    'server/api/handler.ts',
    'export { probe } from "../.scala-kyo/entry"\n',
  )

  // Exercise the same Rollup installation used by this application's Nitro.
  const nuxtRequire = createRequire(import.meta.resolve('nuxt'))
  const nitroRequire = createRequire(
    nuxtRequire.resolve('nitropack/package.json'),
  )
  const { rollup } = await import(pathToFileURL(
    nitroRequire.resolve('rollup'),
  ).href)
  const bundle = await rollup({
    input: entry,
    plugins: [
      {
        name: 'ordinary-resolver',
        resolveId(source) {
          assert.ok(
            !source.includes('.scala-kyo'),
            'externalizer must run before ordinary resolvers',
          )
          return null
        },
        load(id) {
          assert.ok(
            !id.includes('.scala-kyo'),
            'linked code must never enter Rollup',
          )
          return null
        },
      },
      f.plugin,
    ],
  })
  t.after(() => bundle.close())
  const { output } = await bundle.generate({ format: 'es' })
  assert.deepEqual(output[0].imports, [pathToFileURL(linked).href])
  const bundled = await f.put('nitro/output.mjs', output[0].code)
  const { stdout } = await run(process.execPath, [
    '--enable-source-maps',
    '--input-type=module',
    '-e',
    'const { probe } = await import(process.argv[1]); try { probe() } catch (error) { console.log(error.stack) }',
    pathToFileURL(bundled).href,
  ])
  assert.ok(stdout.includes('native map probe'))
  assert.ok(stdout.includes(`${scala}:2:`), stdout)
  assert.equal(await readFile(internal, 'utf8'), internalCode)
  assert.equal(await readFile(`${internal}.map`, 'utf8'), map)
})
