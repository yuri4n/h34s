import { file } from './regions.mjs'

// claude 2026-10-20: created: the entries of a new Nuxt app (ADR-0009)
/** The Nuxt entries of a new app. */
export const nuxtEntries = ['.output', '.data', '.nuxt', '.nitro', '.cache', 'dist', 'node_modules', 'logs', '*.log', '.DS_Store', '.env', '.env.*', '!.env.example']

// claude 2026-10-20: created: the entries of an sbt build (ADR-0009)
/** The sbt entries of a build at the app root (layout A). */
export const sbtEntries = ['.metals/', '.bsp/', 'target/', 'project/target/', 'project/project/', 'project/metals.sbt']

// claude 2026-10-20: created: the linked output (ADR-0009)
/** The linked Scala.js output, which every production build removes. */
export const outputEntries = ['server/.scala/']

// claude 2026-10-20: created: the entries init writes or appends, by layout (ADR-0009)
/**
 * The entries of the app's `.gitignore`: Nuxt's only for a new app, and sbt's only when the sbt
 * build is the app root.
 */
export function entries({ sbtAtApp, nuxt = true }) {
  return [...(nuxt ? nuxtEntries : []), ...(sbtAtApp ? sbtEntries : []), ...outputEntries]
}

// claude 2026-10-20: created: a new .gitignore (ADR-0009)
/** Renders a new `.gitignore`. */
export function render({ sbtAtApp }) {
  return file([
    '# Nuxt',
    ...nuxtEntries,
    ...(sbtAtApp ? ['', '# sbt and Metals', ...sbtEntries] : []),
    '',
    '# h34s: linked Scala.js, which every production build removes',
    ...outputEntries,
  ])
}
