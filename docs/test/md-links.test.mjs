// claude 2026-10-20: created: unit tests of the remark link plugin (issue #25)
import assert from 'node:assert/strict'
import test from 'node:test'
import remarkMdLinks, { headingId, mapLink } from '../remark/md-links.mjs'

const blob = 'https://github.com/yuri4n/h34s/blob/main'
const options = {
  contentRoot: '/repo/docs/content/',
  repositoryRoot: '/repo/',
  repository: 'https://github.com/yuri4n/h34s',
  branch: 'main',
}
const guide = '/repo/docs/content/1.guide/04.zio.md'

function link(url) {
  return { type: 'link', url, children: [{ type: 'text', value: 'text' }] }
}

function heading(depth, value) {
  return { type: 'heading', depth, children: [{ type: 'text', value }] }
}

function run(children, file) {
  const tree = { type: 'root', children }
  remarkMdLinks(options)(tree, file)
  return tree
}

test('a link to a guide in the same section becomes an absolute route', () => {
  assert.equal(mapLink('03.kyo.md', guide, options), '/guide/kyo')
  assert.equal(
    mapLink('03.kyo.md#fail-safely', guide, options),
    '/guide/kyo#fail-safely',
  )
})

test('a link to a guide in another section becomes an absolute route', () => {
  assert.equal(
    mapLink('../2.reference/4.migration.md#wire-format', guide, options),
    '/reference/migration#wire-format',
  )
  assert.equal(mapLink('../index.md', guide, options), '/')
})

test('a digit-leading fragment gets the MDC heading id', () => {
  assert.equal(headingId('1-start-a-new-project'), '_1-start-a-new-project')
  assert.equal(
    mapLink('02.adoption.md#1-start-a-new-project', guide, options),
    '/guide/adoption#_1-start-a-new-project',
  )
  assert.equal(
    mapLink('#2-add-scala-handlers', guide, options),
    '#_2-add-scala-handlers',
  )
  assert.equal(mapLink('#fail-safely', guide, options), '#fail-safely')
})

test('a link that leaves the collection becomes a GitHub URL', () => {
  assert.equal(
    mapLink('../../development.md#production-build', guide, options),
    `${blob}/docs/development.md#production-build`,
  )
  assert.equal(
    mapLink(
      '../../../.github/release-notes/0.2.0-alpha.1.md',
      '/repo/docs/content/2.reference/2.releases.md',
      options,
    ),
    `${blob}/.github/release-notes/0.2.0-alpha.1.md`,
  )
  // A GitHub URL keeps the GitHub slug, digits included.
  assert.equal(
    mapLink('../../development.md#1-x', guide, options),
    `${blob}/docs/development.md#1-x`,
  )
})

test('absolute, scheme and outside-repository links stay as they are', () => {
  for (const url of [
    'https://example.com/a.md',
    'mailto:someone@example.com',
    '/raw/guide/kyo.md',
    '/guide/kyo',
  ]) {
    assert.equal(mapLink(url, guide, options), url)
  }
  assert.equal(mapLink('../../../../outside.md', guide, options), undefined)
})

test('the plugin rewrites links and definitions, not images', () => {
  const image = { type: 'image', url: '../../review/kyo.png', alt: 'Kyo' }
  const definition = { type: 'definition', identifier: 'kyo', url: '03.kyo.md' }
  const tree = run([
    { type: 'paragraph', children: [link('03.kyo.md#fail-safely'), image] },
    definition,
  ], { path: guide, data: {} })
  assert.equal(tree.children[0].children[0].url, '/guide/kyo#fail-safely')
  assert.equal(image.url, '../../review/kyo.png')
  assert.equal(definition.url, '/guide/kyo')
})

test('the plugin leaves a tree without a file path alone', () => {
  const tree = run(
    [heading(1, 'Title'), { type: 'paragraph', children: [link('03.kyo.md')] }],
    { data: { title: 'Title' } },
  )
  assert.equal(tree.children[0].type, 'heading')
  assert.equal(tree.children[1].children[0].url, '03.kyo.md')
})

test('the plugin drops the leading h1 only with a frontmatter title', () => {
  const titled = run(
    [heading(1, 'ZIO handlers'), heading(2, 'Install')],
    { path: guide, data: { title: 'ZIO handlers' } },
  )
  assert.deepEqual(titled.children.map(node => node.depth), [2])

  const untitled = run(
    [heading(1, 'ZIO handlers'), heading(2, 'Install')],
    { path: guide, data: {} },
  )
  assert.deepEqual(untitled.children.map(node => node.depth), [1, 2])

  const commented = run(
    [
      { type: 'html', value: '<!-- claude 2026-10-20: changed: title -->' },
      heading(1, 'Releases'),
      heading(2, 'Toolchain'),
    ],
    { path: guide, data: { title: 'Releases' } },
  )
  assert.deepEqual(
    commented.children.map(node => node.type),
    ['html', 'heading'],
  )
  assert.equal(commented.children[1].depth, 2)

  const laterH1 = run(
    [{ type: 'paragraph', children: [] }, heading(1, 'Late')],
    { path: guide, data: { title: 'Title' } },
  )
  assert.equal(laterH1.children.length, 2)
})
