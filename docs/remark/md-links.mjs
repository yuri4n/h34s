// claude 2026-10-20: created: what the plugin does and why it has no imports
// The guides under content/ are plain Markdown that GitHub renders: their
// links are relative paths to real .md files, with GitHub heading slugs as
// fragments. This remark plugin rewrites those links for the site, before
// Nuxt Content turns them into relative routes:
//
// - a link to a .md file inside content/ becomes an absolute route
//   (`03.kyo.md#fail-safely` in content/1.guide/ is
//   `/guide/kyo#fail-safely`);
// - a link to another file of the repository becomes a GitHub `blob` URL;
// - a fragment that targets a content page gets the MDC heading id, which
//   adds `_` before a leading digit (`#1-start` is `#_1-start` on the site).
//
// It also drops the page's leading h1 when the frontmatter has a title,
// because Docus renders that title above the body.
//
// The file has no imports, so the generated #mdc-imports template can load it
// from anywhere. Paths are POSIX paths.

// claude 2026-10-20: created: the options type for the JSDoc below
/**
 * @typedef {object} MdLinksOptions
 * @property {string} contentRoot The absolute path of content/.
 * @property {string} repositoryRoot The absolute path of the repository.
 * @property {string} repository The GitHub repository URL.
 * @property {string} branch The branch that GitHub `blob` URLs point at.
 */

// claude 2026-10-20: created: what a scheme and an HTML comment look like
const SCHEME = /^[a-z][a-z\d+.-]*:/i
const COMMENT = /^\s*<!--[\s\S]*-->\s*$/

// claude 2026-10-20: created: GitHub slug to MDC heading id
/**
 * The MDC heading id for a GitHub heading slug.
 * @param {string} fragment A GitHub heading slug, without `#`.
 * @returns {string} The id that @nuxtjs/mdc gives the same heading.
 */
export function headingId(fragment) {
  return fragment
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')
    .replace(/^(\d)/, '_$1')
}

// claude 2026-10-20: created: a path with a trailing slash
function directory(path) {
  return path.endsWith('/') ? path : `${path}/`
}

// claude 2026-10-20: created: the link mapping
/**
 * Maps one link URL of a content file to its site form.
 * @param {string} url The URL as written in the Markdown source.
 * @param {string} filePath The absolute path of the Markdown file.
 * @param {MdLinksOptions} options The plugin options.
 * @returns {string | undefined} The site URL, or undefined when the link
 *   points outside the repository.
 */
export function mapLink(url, filePath, options) {
  if (!url || url.startsWith('/') || SCHEME.test(url))
    return url
  const hashAt = url.indexOf('#')
  const path = hashAt === -1 ? url : url.slice(0, hashAt)
  const fragment = hashAt === -1 ? '' : url.slice(hashAt + 1)
  if (!path)
    return `#${headingId(fragment)}`
  const target = decodeURIComponent(
    new URL(path, `file://${filePath}`).pathname,
  )
  const contentRoot = directory(options.contentRoot)
  const repositoryRoot = directory(options.repositoryRoot)
  if (target.startsWith(contentRoot) && target.endsWith('.md')) {
    const segments = target
      .slice(contentRoot.length, -'.md'.length)
      .split('/')
      .map(segment => segment.replace(/^\d+\./, ''))
    if (segments.at(-1) === 'index')
      segments.pop()
    const route = `/${segments.join('/')}`
    return fragment ? `${route}#${headingId(fragment)}` : route
  }
  if (target.startsWith(repositoryRoot)) {
    const relative = encodeURI(target.slice(repositoryRoot.length))
    const hash = hashAt === -1 ? '' : `#${fragment}`
    return `${options.repository}/blob/${options.branch}/${relative}${hash}`
  }
  return undefined
}

// claude 2026-10-20: created: drops the h1 that repeats the frontmatter title
function dropLeadingTitle(tree) {
  const children = tree.children ?? []
  const first = children.findIndex(node =>
    !(node.type === 'html' && COMMENT.test(node.value ?? '')))
  const node = children[first]
  if (node?.type === 'heading' && node.depth === 1)
    children.splice(first, 1)
}

// claude 2026-10-20: created: a dependency-free tree walk
function visit(node, action) {
  action(node)
  for (const child of node.children ?? [])
    visit(child, action)
}

// claude 2026-10-20: created: the plugin entry point
/**
 * The remark plugin. Register it in nuxt.config.ts under
 * content.build.markdown.remarkPlugins.
 * @param {MdLinksOptions} options The plugin options.
 */
export default function remarkMdLinks(options) {
  return (tree, file) => {
    // A runtime parse has no file path; leave its links alone.
    if (!file?.path)
      return
    if (file.data?.title)
      dropLeadingTitle(tree)
    visit(tree, (node) => {
      if (node.type !== 'link' && node.type !== 'definition')
        return
      if (typeof node.url !== 'string')
        return
      const mapped = mapLink(node.url, file.path, options)
      if (mapped === undefined)
        console.warn(`[h34s-md-links] ${file.path}: cannot map ${node.url}`)
      else
        node.url = mapped
    })
  }
}
