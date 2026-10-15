import { readFile, realpath } from 'node:fs/promises'
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

function within(directory, file) {
  const path = relative(directory, file)
  return path !== '..' && !path.startsWith(`..${sep}`) && !isAbsolute(path)
}

// Supply incoming maps before Nitro transforms the linked JS, allowing Rollup
// to compose both stages back to Scala source locations.
export function scalaSourceMaps(
  projectRoot,
  outputDirectories = ['server/.scala'],
) {
  const root = resolve(projectRoot)
  const linkedDirectories = outputDirectories.map(directory =>
    resolve(root, directory),
  )

  return {
    name: 'h34s-scala-source-maps',
    async load(id) {
      const linked
        = isAbsolute(id)
          && linkedDirectories.some(directory => within(directory, id))
      if (!linked || !id.endsWith('.js')) {
        return null
      }
      const mapPath = `${id}.map`
      this.addWatchFile(mapPath)
      const [code, rawMap] = await Promise.all([
        readFile(id, 'utf8'),
        readFile(mapPath, 'utf8'),
      ])
      const map = JSON.parse(rawMap)
      const localRoot = await realpath(root)
      async function resolveSource(source, index) {
        let location
        if (URL.canParse(source)) {
          location = source
        }
        else if (map.sourceRoot && URL.canParse(map.sourceRoot)) {
          const base = `${map.sourceRoot.replace(/\/$/, '')}/`
          location = new URL(source, base).href
        }
        else {
          location = resolve(dirname(mapPath), map.sourceRoot ?? '', source)
        }
        if (location.startsWith('file:')) {
          location = fileURLToPath(location)
        }

        let content = map.sourcesContent?.[index] ?? null
        const local = isAbsolute(location) && within(root, location)
        // Never fetch dependency URLs. Embed only local project Scala sources.
        if (content === null && local && location.endsWith('.scala')) {
          try {
            const actual = await realpath(location)
            if (within(localRoot, actual)) {
              content = await readFile(actual, 'utf8')
            }
          }
          catch (error) {
            if (error.code !== 'ENOENT') {
              throw error
            }
          }
        }
        return { location, content }
      }
      const sources = await Promise.all(map.sources.map(resolveSource))
      map.sources = sources.map(source => source.location)
      map.sourcesContent = sources.map(source => source.content)
      delete map.sourceRoot
      return { code, map }
    },
  }
}
