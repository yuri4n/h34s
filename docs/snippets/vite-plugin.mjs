// A Vite plugin that serves `virtual:h34s-snippets`: the texts of
// sources.mjs, highlighted with Shiki at build time. The HTML carries both
// themes, so the page needs no highlighter in the browser.
import { createHighlighter } from 'shiki'

import { loadSnippets, readRepositoryFile, repositoryRoot } from './sources.mjs'

const id = 'virtual:h34s-snippets'
const resolved = `\0${id}`

// claude 2026-10-20: created: the themes of the Content highlighter (#52)
// The same pair as `content.build.markdown.highlight` in docs/nuxt.config.ts.
const themes = { light: 'github-light', dark: 'github-dark' }

// claude 2026-10-20: created: Shiki HTML with a class on changed lines (#52)
function highlight(highlighter, text, lang, changed) {
  const marked = new Set(changed)
  return highlighter.codeToHtml(text, {
    lang,
    themes,
    defaultColor: 'light',
    transformers: [{
      pre(node) {
        // The panel around the code holds the label and the focus.
        delete node.properties.tabindex
      },
      line(node, line) {
        if (marked.has(line))
          this.addClassToHast(node, 'snippet-line-changed')
      },
    }],
  })
}

// claude 2026-10-20: created: the virtual module of the shown code (#52)
export function h34sSnippets() {
  let highlighter
  return {
    name: 'h34s-snippets',
    enforce: 'pre',
    resolveId(source) {
      return source === id ? resolved : undefined
    },
    async load(source) {
      if (source !== resolved)
        return undefined
      // 'text' is built in: plain lines, no tokens.
      highlighter ??= await createHighlighter({
        themes: Object.values(themes),
        langs: ['scala', 'ts', 'js', 'json', 'properties'],
      })
      const read = new Set()
      const snippets = loadSnippets((path) => {
        read.add(path)
        return readRepositoryFile(path)
      })
      for (const path of read)
        this.addWatchFile(`${repositoryRoot}${path}`)
      const render = (text, lang, compare) => ({
        ...text,
        changed: compare ? text.changed : [],
        html: highlight(
          highlighter,
          text.text,
          lang,
          compare ? text.changed : [],
        ),
      })
      const greeting = {
        kyo: render(snippets.greeting.kyo, 'scala', true),
        zio: render(snippets.greeting.zio, 'scala', true),
      }
      const files = Object.fromEntries(
        Object.entries(snippets.files).map(([key, file]) => [key, {
          lang: file.lang,
          shared: file.shared,
          compare: file.compare,
          kyo: render(file.kyo, file.lang, file.compare),
          zio: render(file.zio, file.lang, file.compare),
        }]),
      )
      // Named exports: a page that imports only `greeting` ships no tour.
      return [
        `export const greeting = ${JSON.stringify(greeting)}`,
        `export const tree = ${JSON.stringify(snippets.tree)}`,
        `export const files = ${JSON.stringify(files)}`,
        '',
      ].join('\n')
    },
  }
}
