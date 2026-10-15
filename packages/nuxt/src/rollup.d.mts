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
export function scalaSourceMaps(
  projectRoot: string,
  outputDirectories?: string[],
): SourceMapPlugin
