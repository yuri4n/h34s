// The h34s runtime contract between Nitro and Scala.js handlers. Scala reads these records
// through internal facades only; application code never sees them.
//
// Kept free of Nitro imports so tests can inject `useRuntimeConfig` and `useStorage`.

export const H34S_NITRO = Symbol.for('h34s.nitro')

/**
 * @param {{
 *   useRuntimeConfig: (event?: unknown) => unknown,
 *   useStorage: (base?: string) => unknown,
 *   development: boolean,
 * }} nitro
 */
export function createH34sPlugin({ useRuntimeConfig, useStorage, development }) {
  // Only the boolean `true` enables diagnostics; requests can never change it.
  const dev = development === true
  return function h34s(nitroApp) {
    const closers = new Set()
    const startup = Object.freeze({
      version: 1,
      development: dev,
      // Nitro's shared, frozen startup copy (no event): for application-scoped resources.
      runtimeConfig: () => useRuntimeConfig(),
      storage: (base = '') => useStorage(base),
      onClose: (close) => {
        closers.add(close)
        return () => closers.delete(close)
      },
    })
    globalThis[H34S_NITRO] = startup

    nitroApp.hooks.hook('request', (event) => {
      event.context.h34s = Object.freeze({
        version: 1,
        development: dev,
        // Lazy: Nitro clones the config and applies NUXT_* overrides only when asked, then
        // caches it on the event.
        runtimeConfig: () => useRuntimeConfig(event),
        storage: (base = '') => useStorage(base),
      })
    })

    nitroApp.hooks.hook('close', async () => {
      const pending = [...closers]
      closers.clear()
      // One failing release must not stop the others.
      await Promise.allSettled(pending.map(close => Promise.resolve().then(close)))
      if (globalThis[H34S_NITRO] === startup)
        delete globalThis[H34S_NITRO]
    })
  }
}
