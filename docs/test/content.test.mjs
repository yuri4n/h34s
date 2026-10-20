import assert from 'node:assert/strict'
import { existsSync, statSync } from 'node:fs'
import { readdir, readFile } from 'node:fs/promises'
import { dirname, relative, resolve } from 'node:path'
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

test('content and component links resolve to a content page', async () => {
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
  // claude 2026-10-20: changed: every Vue file under app/, not only the
  // homepage, because the header and footer link pages too
  const vueFiles = (await filesUnder(resolve(root, 'app')))
    .filter(file => file.endsWith('.vue'))
  assert.ok(vueFiles.length > 1, 'The site components must be present')
  for (const file of [...contentFiles, ...vueFiles]) {
    const source = await readFile(file, 'utf8')
    const links = file.endsWith('.vue')
      ? [...source.matchAll(/\bto(?:=|: )['"](\/[^'"]*)['"]/g)]
      : [...source.matchAll(/\]\((\/[^)\s]*)\)/g)]
    for (const [, target] of links) {
      const route = target.split('#')[0]
      assert.ok(routes.has(route), `${file}: broken internal link ${target}`)
    }
  }
})

// claude 2026-10-20: created: the folders that hold no documentation source
const skipped = new Set([
  'node_modules',
  'vendor',
  '.nuxt',
  '.output',
  '.data',
  '.vercel',
])

// claude 2026-10-20: created: Markdown lines outside frontmatter and fences
function proseLines(source) {
  const lines = source.split('\n')
  let start = 0
  if (lines[0] === '---') {
    const end = lines.indexOf('---', 1)
    start = end === -1 ? 0 : end + 1
  }
  const result = []
  let fence = null
  for (let index = start; index < lines.length; index++) {
    const line = lines[index]
    const marker = /^\s*(`{3,}|~{3,})/.exec(line)
    if (fence) {
      if (marker && marker[1][0] === fence[0]
        && marker[1].length >= fence.length) {
        fence = null
      }
      continue
    }
    if (marker) {
      fence = marker[1]
      continue
    }
    result.push({ line, number: index + 1 })
  }
  return result
}

// claude 2026-10-20: created: GitHub's heading slugs (github-slugger's rule)
function headingSlugs(source) {
  const seen = new Map()
  const slugs = new Set()
  for (const { line } of proseLines(source)) {
    const heading = /^#{1,6}[ \t](.*)$/.exec(line)
    if (!heading)
      continue
    const text = heading[1]
      .trim()
      .replace(/(?:^|[ \t])#+$/, '')
      .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')
      .replace(/<[^>]+>/g, '')
      .split(/(`+[^`]*`+)/)
      .map(part => part.startsWith('`')
        ? part
        : part.replace(/\*+/g, '').replace(/(^|\W)_+|_+(?=\W|$)/g, '$1'))
      .join('')
    let slug = text
      .toLowerCase()
      .replace(/[^\p{L}\p{N}\p{M}\s_-]/gu, '')
      .replace(/\s/g, '-')
    const count = seen.get(slug) ?? 0
    seen.set(slug, count + 1)
    if (count)
      slug = `${slug}-${count}`
    slugs.add(slug)
  }
  return slugs
}

// claude 2026-10-20: created: Markdown links outside code, with their line
function markdownLinks(source) {
  const links = []
  for (const { line, number } of proseLines(source)) {
    const prose = line.replace(/(`+)[\s\S]*?\1/g, '')
    const pattern = /(!?)\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)/g
    for (const [, image, url] of prose.matchAll(pattern))
      links.push({ image: image === '!', url, number })
    for (const [, url] of prose.matchAll(/^\s*\[[^\]]+\]:\s*(\S+)/g))
      links.push({ image: false, url, number })
  }
  return links
}

// claude 2026-10-20: created: relative links work on GitHub and map to the site
test('relative Markdown links resolve to a file and a heading', async () => {
  const contentRoot = resolve(root, 'content')
  const files = []
  async function collect(directory) {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      if (skipped.has(entry.name))
        continue
      const path = resolve(directory, entry.name)
      if (entry.isDirectory())
        await collect(path)
      else if (entry.name.endsWith('.md'))
        files.push(path)
    }
  }
  await collect(root)
  assert.ok(files.length > 20, 'The guides and maintainer notes must exist')
  const slugCache = new Map()
  async function slugsOf(file) {
    if (!slugCache.has(file))
      slugCache.set(file, headingSlugs(await readFile(file, 'utf8')))
    return slugCache.get(file)
  }
  const problems = []
  for (const file of files) {
    const inContent = file.startsWith(`${contentRoot}/`)
    const where = number => `${relative(root, file)}:${number}`
    for (const { image, url, number } of markdownLinks(
      await readFile(file, 'utf8'),
    )) {
      if (/^[a-z][a-z\d+.-]*:/i.test(url))
        continue
      if (url.startsWith('/')) {
        if (inContent)
          problems.push(`${where(number)}: use a relative .md link, not ${url}`)
        continue
      }
      if (image && inContent) {
        problems.push(`${where(number)}: relative image ${url} is not mapped`)
        continue
      }
      const [path, fragment] = url.split('#')
      const target = path
        ? resolve(dirname(file), decodeURIComponent(path))
        : file
      if (!existsSync(target)) {
        problems.push(`${where(number)}: missing ${url}`)
        continue
      }
      const isMarkdown = target.endsWith('.md') && statSync(target).isFile()
      if (inContent && target.startsWith(`${contentRoot}/`) && !isMarkdown)
        problems.push(`${where(number)}: ${url} is not a .md page`)
      if (fragment && isMarkdown && !(await slugsOf(target)).has(fragment))
        problems.push(`${where(number)}: no heading for #${fragment} in ${url}`)
    }
  }
  assert.deepEqual(problems, [])
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
