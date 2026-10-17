// Test-only: Nitro aliases `nitropack/runtime` to its own runtime inside a build. Plain Node
// cannot load that runtime (it imports Nitro's virtual modules), so resolve it to a stub that
// delegates to a hub the tests control.
import { register } from 'node:module'

const HUB = Symbol.for('h34s.test.nitro')

const stub = `
const hub = () => globalThis[Symbol.for('h34s.test.nitro')]
export const useRuntimeConfig = (...args) => hub().useRuntimeConfig(...args)
export const useStorage = (...args) => hub().useStorage(...args)
`

const hooks = `
export async function resolve(specifier, context, next) {
  if (specifier === 'nitropack/runtime')
    return { url: 'data:text/javascript,' + encodeURIComponent(${JSON.stringify(stub)}), shortCircuit: true }
  return next(specifier, context)
}
`

register(`data:text/javascript,${encodeURIComponent(hooks)}`)

globalThis[HUB] ??= {
  calls: [],
  useRuntimeConfig(...args) {
    this.calls.push(['useRuntimeConfig', ...args])
    return { apiBase: '/api' }
  },
  useStorage(...args) {
    this.calls.push(['useStorage', ...args])
    return { mount: args[0] }
  },
}

export const nitroHub = globalThis[HUB]
