import { pathToFileURL } from 'node:url'
import { scalaOutputResolver } from './scala-outputs.mjs'

// In development Node loads linked Scala.js directly, including its adjacent
// source maps. Nitro must separately watch these directories and restart its
// worker after linking so Node's ESM module cache is replaced.
// claude 2026-10-19: changed: the output-file resolution moved to scala-outputs.mjs, behavior unchanged
export function scalaDevModules(
  projectRoot,
  outputDirectories = ['server/.scala', 'server/.scala-kyo'],
) {
  const resolveOutput = scalaOutputResolver(projectRoot, outputDirectories)

  return {
    name: 'h34s-scala-dev-modules',
    resolveId: {
      // Run before Nitro's normal resolvers, even when this plugin is appended.
      order: /** @type {'pre'} */ ('pre'),
      async handler(source, importer) {
        const actualFile = await resolveOutput(source, importer)
        return actualFile
          ? { id: pathToFileURL(actualFile).href, external: true }
          : null
      },
    },
  }
}
