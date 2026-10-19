// claude 2026-10-19: created: keeps linked Scala.js out of Nitro's prerender bundle for scalaPrerender: 'stub' (issue #30)
import { scalaOutputResolver } from './scala-outputs.mjs'

const stubId = '\0h34s-scala-prerender-stub'
// Rollup's syntheticNamedExports: every named import from an output file reads this object.
const namedExports = '__h34sScalaPrerenderStub'
const statusMessage = 'Scala.js is not bundled for prerendering (h34s scalaPrerender: \'stub\')'
const message = 'This route runs Scala.js code. @yuri4n/h34s-nuxt leaves Scala.js out of Nitro\'s prerender bundle because its option scalaPrerender is \'stub\'. Do not prerender pages that call Scala.js routes, or remove the option to bundle Scala.js for prerendering.'

// The stub needs no imports, so no resolver has to find h3 from a virtual module. h3 1.x
// recognizes an event handler by the `__is_handler__` marker that `defineEventHandler` sets, and
// its `createError` turns the thrown `statusCode` and `statusMessage` into a 501 response.
const stubSource = `const statusMessage = ${JSON.stringify(statusMessage)}
const message = ${JSON.stringify(message)}
function scalaPrerenderStub() {
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
