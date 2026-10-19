import type { NuxtModule } from 'nuxt/schema'

export interface H34sNuxtOptions {
  configFile?: string
  // claude 2026-10-19: created: the scalaPrerender option (issue #30)
  /**
   * How Nitro's prerender bundle gets the linked Scala.js in production builds.
   * `'bundle'` (the default) bundles it, as the server build does. `'stub'` replaces every
   * import of a configured output file with a handler that answers 501 while Nuxt prerenders,
   * so the build bundles the Scala.js once. The server output always bundles it.
   */
  scalaPrerender?: 'bundle' | 'stub'
}
declare const h34sNuxt: NuxtModule<H34sNuxtOptions>
export default h34sNuxt
