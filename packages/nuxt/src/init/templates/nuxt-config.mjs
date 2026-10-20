import { file } from './regions.mjs'

// claude 2026-10-20: created: the module name (ADR-0009)
/** The module name that init adds to `modules`. */
export const moduleName = '@yuri4n/h34s-nuxt'

// claude 2026-10-20: created: a new app's nuxt.config.ts with the module and the node-server preset (ADR-0009, decision 9)
/** Renders `nuxt.config.ts` for a Nuxt app that has none. */
export function render() {
  return file([
    '// https://nuxt.com/docs/api/configuration/nuxt-config',
    'export default defineNuxtConfig({',
    '  compatibilityDate: \'2025-05-15\',',
    `  modules: ['${moduleName}'],`,
    '  nitro: {',
    '    // h34s runs the linked Scala.js inside Nitro on Node.',
    '    preset: \'node-server\',',
    '  },',
    '})',
  ])
}
