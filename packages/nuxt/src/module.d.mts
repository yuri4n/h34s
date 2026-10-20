import type { NuxtModule } from 'nuxt/schema'

export interface H34sNuxtOptions {
  configFile?: string
  // claude 2026-10-19: created: the scalaPrerender option (issue #30), and the build error when a prerendered page reaches the stub (review F1)
  /**
   * How Nitro's prerender bundle gets the linked Scala.js in production builds.
   * `'bundle'` (the default) bundles it, as the server build does. `'stub'` replaces every
   * import of a configured output file with a handler that answers 501 while Nuxt prerenders,
   * so the build bundles the Scala.js once. The server output always bundles it. When a request
   * reaches the stub while Nuxt prerenders, the build stops after prerendering with a list of
   * those requests; with `nitro.prerender.failOnError: false`, it only warns.
   */
  scalaPrerender?: 'bundle' | 'stub'
}
declare const h34sNuxt: NuxtModule<H34sNuxtOptions>
export default h34sNuxt
