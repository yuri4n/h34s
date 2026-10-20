import { readFileSync } from 'node:fs'

// claude 2026-10-20: created: the package's own version, which init pins and the module's error hint names (ADR-0009)
/** The version of this `@yuri4n/h34s-nuxt` package, read from its `package.json`. */
export function packageVersion() {
  return JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version
}
