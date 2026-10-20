// The code that the homepage and the project tour show, and the source of
// each text. Every text is read from a file at build time.
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  extractMarkedFence,
  extractRegion,
  lineDiff,
  zioBuild,
} from './extract.mjs'

// claude 2026-10-20: created: the repository root (#52)
export const repositoryRoot = fileURLToPath(new URL('../../', import.meta.url))

const kyo = 'examples/nuxt/scala/kyo/src/main/scala/dev/yuri4n/kyoexample'
const zio = 'examples/nuxt/scala/zio/src/main/scala/dev/yuri4n/svr'
const adoption = 'docs/content/1.guide/02.adoption.md'
const npmTooling = 'docs/content/1.guide/13.npm-tooling.md'
const nuxtConfig = 'examples/nuxt/nuxt.config.ts'

// claude 2026-10-20: created: the two kinds of source (#52)
/** A region of a compiled source file, or a marked block of a guide. */
const region = (file, name) => ({ kind: 'region', file, name })
const guide = (file, name) => ({ kind: 'guide', file, name })

// claude 2026-10-20: created: the homepage's handler in both runtimes (#52)
export const greeting = {
  kyo: region(`${kyo}/Handlers.scala`, 'greeting-handler'),
  zio: region(`${zio}/handlers/GreetingHandler.scala`, 'greeting-handler'),
}

// claude 2026-10-20: changed: layout A (ADR-0009, decision 7) (#52)
// The ZIO handler keeps its real file name.
/**
 * The tree of a new project in layout A. An entry with an `id` is a file
 * that the tour can open; the others show structure only. `generated` marks
 * output that a build writes. `zioPath` is the entry's path when the tour
 * shows ZIO, because the ZIO demo keeps that file under another name.
 */
export const tree = [
  { path: 'build.sbt', id: 'build.sbt' },
  { path: '.jvmopts', id: '.jvmopts' },
  { path: 'project/', directory: true },
  { path: 'project/build.properties', id: 'project/build.properties' },
  { path: 'project/plugins.sbt', id: 'project/plugins.sbt' },
  { path: 'scala-server/src/main/scala/', directory: true },
  {
    path: 'scala-server/src/main/scala/Handlers.scala',
    zioPath: 'scala-server/src/main/scala/GreetingHandler.scala',
    id: 'Handlers.scala',
  },
  {
    path: 'scala-server/src/main/scala/Greetings.scala',
    id: 'Greetings.scala',
  },
  {
    path: 'scala-server/src/main/scala/AppHandler.scala',
    id: 'AppHandler.scala',
  },
  { path: 'server/.scala/', directory: true, generated: true },
  { path: 'app/', directory: true },
  { path: 'h34s.config.mjs', id: 'h34s.config.mjs' },
  { path: 'nuxt.config.ts', id: 'nuxt.config.ts' },
  { path: 'package.json', id: 'package.json' },
]

// claude 2026-10-20: created: the files that the tour opens (#52)
/**
 * Each file has a language and a source: one for both runtimes (`both`) or
 * one per runtime. `zioFrom: 'kyo-build'` applies the adoption guide's rule
 * to the Kyo build. `compare` marks the files whose two runtimes keep the
 * same structure, so the tour highlights their changed lines.
 */
export const files = {
  'build.sbt': {
    lang: 'scala',
    kyo: guide(adoption, 'build.sbt'),
    zioFrom: 'kyo-build',
    compare: true,
  },
  '.jvmopts': { lang: 'text', both: guide(adoption, '.jvmopts') },
  'project/build.properties': {
    lang: 'properties',
    both: guide(adoption, 'project/build.properties'),
  },
  'project/plugins.sbt': {
    lang: 'scala',
    both: guide(adoption, 'project/plugins.sbt'),
  },
  'Handlers.scala': {
    lang: 'scala',
    compare: true,
    kyo: region(`${kyo}/Handlers.scala`, 'tour'),
    zio: region(`${zio}/handlers/GreetingHandler.scala`, 'tour'),
  },
  'Greetings.scala': {
    lang: 'scala',
    kyo: region(`${kyo}/Greetings.scala`, 'tour'),
    zio: region(`${zio}/greetings/Greetings.scala`, 'tour'),
  },
  'AppHandler.scala': {
    lang: 'scala',
    kyo: region(`${kyo}/AppHandler.scala`, 'tour'),
    zio: region(`${zio}/app/AppHandler.scala`, 'tour'),
  },
  'h34s.config.mjs': { lang: 'js', both: guide(adoption, 'h34s.config.mjs') },
  'nuxt.config.ts': {
    lang: 'ts',
    both: region(nuxtConfig, 'tour-nuxt-config'),
  },
  'package.json': { lang: 'json', both: guide(npmTooling, 'package.json') },
}

// claude 2026-10-20: created: every source that the site reads (#52)
/** Gives every region and guide marker that the site shows, flattened. */
export function allSources() {
  return [
    ...Object.values(greeting),
    ...Object.values(files).flatMap(entry =>
      [entry.both, entry.kyo, entry.zio].filter(Boolean)),
  ]
}

// claude 2026-10-20: created: reads one source (#52)
/** Gives the text of a source. `read` maps a repository path to its text. */
export function readSource(source, read) {
  const text = read(source.file)
  return source.kind === 'region'
    ? extractRegion(text, source.name, source.file)
    : extractMarkedFence(text, source.name, source.file)
}

// claude 2026-10-20: created: the caption that names a text's source (#52)
export function sourceCaption(source) {
  if (source.file === adoption)
    return 'From the adoption guide'
  if (source.file === npmTooling)
    return 'From the npm tooling guide'
  return `From ${source.file}`
}

// claude 2026-10-20: created: reads a file by its repository path (#52)
export function readRepositoryFile(path, root = repositoryRoot) {
  return readFileSync(resolve(root, path), 'utf8')
}

// claude 2026-10-20: created: one runtime's text with its source (#52)
function variant(text, changed, source) {
  return { text, changed, caption: sourceCaption(source), file: source.file }
}

// claude 2026-10-20: created: two runtimes' texts and changed lines (#52)
function pair(kyoText, zioText, kyoSource, zioSource) {
  const diff = lineDiff(kyoText, zioText)
  return {
    kyo: variant(kyoText, diff.left, kyoSource),
    zio: variant(zioText, diff.right, zioSource),
  }
}

// claude 2026-10-20: created: one tour file in both runtimes (#52)
function tourFile(entry, read) {
  const { lang } = entry
  if (entry.both) {
    const text = variant(readSource(entry.both, read), [], entry.both)
    return { lang, shared: true, compare: false, kyo: text, zio: text }
  }
  const kyoText = readSource(entry.kyo, read)
  const derived = entry.zioFrom === 'kyo-build'
  const zioText = derived
    ? zioBuild(kyoText, entry.kyo.file)
    : readSource(entry.zio, read)
  const texts = pair(kyoText, zioText, entry.kyo, entry.zio ?? entry.kyo)
  if (derived) {
    texts.zio.caption
      = 'From the adoption guide, with kyo-h3-ext replaced by zio-h3-ext'
  }
  return { lang, shared: false, compare: entry.compare === true, ...texts }
}

// claude 2026-10-20: created: every shown text, before highlighting (#52)
/**
 * Gives every text that the site shows as a plain string, with its source
 * caption and, for two runtimes, the changed lines. The Vite plugin
 * highlights these texts; the tests compare them with the files.
 */
export function loadSnippets(read = path => readRepositoryFile(path)) {
  return {
    greeting: pair(
      readSource(greeting.kyo, read),
      readSource(greeting.zio, read),
      greeting.kyo,
      greeting.zio,
    ),
    tree,
    files: Object.fromEntries(
      Object.entries(files).map(([id, entry]) => [id, tourFile(entry, read)]),
    ),
  }
}
