interface DevModulesPlugin {
  name: string
  resolveId: {
    order: 'pre'
    handler: (
      source: string,
      importer?: string,
    ) => Promise<{ id: string, external: true } | null>
  }
}
// claude 2026-10-19: created: the plugin type of scalaPrerenderStub
interface PrerenderStubPlugin {
  name: string
  resolveId: {
    order: 'pre'
    handler: (source: string, importer?: string) => Promise<string | null>
  }
  load: (id: string) => { code: string, syntheticNamedExports: string } | null
}
interface SourceMapPlugin {
  name: string
  load: (
    this: { addWatchFile: (id: string) => void },
    id: string,
  ) => Promise<{
    code: string
    map: {
      version: number
      sources: string[]
      sourcesContent: (string | null)[]
      names: string[]
      mappings: string
    }
  } | null>
}
export function scalaDevModules(
  projectRoot: string,
  outputDirectories?: string[],
): DevModulesPlugin
// claude 2026-10-19: created: replaces linked Scala.js with a 501 stub; for Nitro's prerender build only
export function scalaPrerenderStub(
  projectRoot: string,
  outputDirectories?: string[],
): PrerenderStubPlugin
// claude 2026-10-20: changed: sourceRoots, the other directories whose Scala sources are embedded (ADR-0009)
/**
 * Supplies the linked Scala.js source maps to Rollup. A map embeds the content of each `.scala`
 * source inside `projectRoot` or one of `sourceRoots` (relative to `projectRoot`, or absolute),
 * such as the sbt root; it keeps the path of every other source without reading it.
 */
export function scalaSourceMaps(
  projectRoot: string,
  outputDirectories?: string[],
  sourceRoots?: string[],
): SourceMapPlugin
