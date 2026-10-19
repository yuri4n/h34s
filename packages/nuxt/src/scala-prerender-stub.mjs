// claude 2026-10-19: created: keeps linked Scala.js out of Nitro's prerender bundle for scalaPrerender: 'stub' (issue #30)
import { scalaOutputResolver } from './scala-outputs.mjs'

const stubId = '\0h34s-scala-prerender-stub'
// Rollup's syntheticNamedExports: every named import from an output file reads this object.
const namedExports = '__h34sScalaPrerenderStub'
const statusMessage = 'Scala.js is not bundled for prerendering (h34s scalaPrerender: \'stub\')'
const message = 'This route runs Scala.js code. @yuri4n/h34s-nuxt leaves Scala.js out of Nitro\'s prerender bundle because its option scalaPrerender is \'stub\'. Do not prerender pages that call Scala.js routes, or remove the option to bundle Scala.js for prerendering.'
// claude 2026-10-19: created: the process-global list of requests that the stub answered (review F1)
// Nitro imports the prerender bundle into the build process, so the stub and the Nuxt module
// share `globalThis`. The module creates the list for one prerender run and reports it at the end.
const callsKey = 'h34s.scalaPrerenderStub.calls'

// The stub needs no imports, so no resolver has to find h3 from a virtual module. h3 1.x
// recognizes an event handler by the `__is_handler__` marker that `defineEventHandler` sets, and
// its `createError` turns the thrown `statusCode` and `statusMessage` into a 501 response.
// claude 2026-10-19: changed: the stub records and logs each request before it throws (review F1)
// h3 strips a mount prefix from `event.path`; `originalUrl` keeps the URL that the page fetched.
const stubSource = `const statusMessage = ${JSON.stringify(statusMessage)}
const message = ${JSON.stringify(message)}
const calls = Symbol.for(${JSON.stringify(callsKey)})
function scalaPrerenderStub(event) {
  const request = event?.node?.req
  const page = request?.headers?.['x-nitro-prerender']
  const call = {
    method: String(event?.method ?? request?.method ?? 'GET'),
    path: String(request?.originalUrl ?? event?.path ?? request?.url ?? '(unknown path)'),
    page: typeof page === 'string' ? page : undefined,
  }
  const recorded = globalThis[calls]
  if (Array.isArray(recorded))
    recorded.push(call)
  console.warn('[h34s] ' + call.method + ' ' + call.path + ' answered 501' + (call.page ? ' while Nuxt prerendered ' + call.page : '') + ': ' + statusMessage + '.')
  const error = new Error(message)
  error.statusCode = 501
  error.statusMessage = statusMessage
  throw error
}
scalaPrerenderStub.__is_handler__ = true
export default scalaPrerenderStub
export const ${namedExports} = new Proxy({}, {
  get: (_target, name) => typeof name === 'string' && name !== 'then' ? scalaPrerenderStub : undefined,
})
`

// Resolves every import of a linked Scala.js file inside the configured output directories to one
// stub module. Use it only in the Rollup build of Nitro's prerenderer: the stub answers every
// request with a 501 error.
export function scalaPrerenderStub(
  projectRoot,
  outputDirectories = ['server/.scala', 'server/.scala-kyo'],
) {
  const resolveOutput = scalaOutputResolver(projectRoot, outputDirectories)

  return {
    name: 'h34s-scala-prerender-stub',
    resolveId: {
      // Run before Nitro's normal resolvers, even when this plugin is appended.
      order: /** @type {'pre'} */ ('pre'),
      async handler(source, importer) {
        return (await resolveOutput(source, importer)) ? stubId : null
      },
    },
    load(id) {
      return id === stubId
        ? { code: stubSource, syntheticNamedExports: namedExports }
        : null
    },
  }
}

// claude 2026-10-19: created: starts a new list of stub requests for one prerender run (review F1)
export function recordStubCalls() {
  globalThis[Symbol.for(callsKey)] = []
}

// claude 2026-10-19: created: returns and removes the requests that the stub answered (review F1)
export function takeStubCalls() {
  const key = Symbol.for(callsKey)
  const calls = globalThis[key]
  delete globalThis[key]
  return Array.isArray(calls) ? calls : []
}

// claude 2026-10-19: created: the build message that lists the requests the stub answered (review F1)
// Nitro prerenders in parallel, so the list is sorted and each request appears once.
export function stubCallReport(calls, limit = 20) {
  const lines = [...new Set(calls.map(call =>
    `  ${call.method} ${call.path}${call.page ? `, while Nuxt prerendered ${call.page}` : ''}`,
  ))].sort()
  const shown = lines.slice(0, limit)
  if (lines.length > limit)
    shown.push(`  and ${lines.length - limit} more`)
  return [
    `@yuri4n/h34s-nuxt: scalaPrerender is 'stub', so Nitro's prerender bundle has no Scala.js. These requests reached a Scala.js route while Nuxt prerendered, and each one got a 501 response instead of running your Scala.js code:`,
    ...shown,
    'A page that catches the error, as useFetch does, would be saved with that error state. Stop prerendering the pages that call Scala.js routes, or remove scalaPrerender: \'stub\' to bundle Scala.js for prerendering.',
  ].join('\n')
}
