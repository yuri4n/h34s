import assert from 'node:assert/strict'
import { readdir, readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../', import.meta.url))

async function filesUnder(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const groups = await Promise.all(entries.map(async (entry) => {
    const path = resolve(directory, entry.name)
    return entry.isDirectory() ? filesUnder(path) : [path]
  }))
  return groups.flat()
}

test('documentation and homepage links resolve to a content page', async () => {
  const contentRoot = resolve(root, 'content')
  const contentFiles = (await filesUnder(contentRoot))
    .filter(file => file.endsWith('.md'))
  assert.ok(contentFiles.length > 1, 'Documentation content must be present')
  const routes = new Set(contentFiles.map((file) => {
    const segments = file
      .slice(contentRoot.length + 1)
      .replace(/\.md$/, '')
      .split('/')
    const path = segments.map(segment => segment.replace(/^\d+\./, ''))
    const route = `/${path.join('/')}`
    return route === '/index' ? '/' : route
  }))
  const home = resolve(root, 'app/pages/index.vue')
  for (const file of [...contentFiles, home]) {
    const source = await readFile(file, 'utf8')
    const links = file === home
      ? [...source.matchAll(/\bto(?:=|: )['"](\/[^'"]+)['"]/g)]
      : [...source.matchAll(/\]\((\/[^)\s]*)\)/g)]
    for (const [, target] of links) {
      const route = target.split('#')[0]
      assert.ok(routes.has(route), `${file}: broken internal link ${target}`)
    }
  }
})

test('landing snippet matches the application source', async () => {
  const homepage = await readFile(
    resolve(root, 'app/pages/index.vue'),
    'utf8',
  )
  const match = /const example = `([^`]+)`/.exec(homepage)
  assert.ok(match, 'Copyable handler example must be present')
  // The Kyo and ZIO demo applications.
  const sourceRoot = resolve(root, '../examples/nuxt/scala')
  const appFiles = (await filesUnder(sourceRoot))
    .filter(file => file.endsWith('.scala'))
  const sources = await Promise.all(
    appFiles.map(file => readFile(file, 'utf8')),
  )
  const normalized = value => value
    .trim()
    .split('\n')
    .map(line => line.trim())
    .join('\n')
  const example = normalized(match[1])
  assert.ok(
    sources.some(source => normalized(source).includes(example)),
    'Homepage example must match a real Scala handler',
  )
})
