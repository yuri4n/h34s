import { realpath, stat } from 'node:fs/promises'
import { dirname, extname, isAbsolute, relative, resolve, sep } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

function within(directory, file) {
  const path = relative(directory, file)
  return path !== '..' && !path.startsWith(`..${sep}`) && !isAbsolute(path)
}

function localPath(id) {
  if (!id || id.includes('\0')) {
    return null
  }
  if (id.startsWith('file:')) {
    try {
      const url = new URL(id)
      return url.search || url.hash ? null : fileURLToPath(url)
    }
    catch {
      return null
    }
  }
  return isAbsolute(id) && !/[?#]/.test(id) ? id : null
}

// In development Node loads linked Scala.js directly, including its adjacent
// source maps. Nitro must separately watch these directories and restart its
// worker after linking so Node's ESM module cache is replaced.
export function scalaDevModules(
  projectRoot,
  outputDirectories = ['server/.scala', 'server/.scala-kyo'],
) {
  const root = resolve(projectRoot)
  const linkedDirectories = outputDirectories.map((directory) => {
    const linked = resolve(root, directory)
    if (linked === root || !within(root, linked)) {
      throw new TypeError(
        'Scala.js output directories must be inside the project root',
      )
    }
    return linked
  })

  return {
    name: 'h34s-scala-dev-modules',
    resolveId: {
      // Run before Nitro's normal resolvers, even when this plugin is appended.
      order: 'pre',
      async handler(source, importer) {
        let candidate = localPath(source)
        if (!candidate && /^\.\.?\//.test(source) && !/[\0?#]/.test(source)) {
          const importingFile = localPath(importer)
          if (importingFile) {
            candidate = resolve(dirname(importingFile), source)
          }
        }
        if (!candidate) {
          return null
        }
        const extension = extname(candidate)
        if (!extension) {
          candidate += '.js'
        }
        else if (extension !== '.js') {
          return null
        }
        try {
          const actualRoot = await realpath(root)
          // Other resolvers can canonicalize importers even when the checkout
          // was opened through a symlink. Keep both spellings bounded to the
          // same configured output directories.
          if (actualRoot !== root && within(actualRoot, candidate)) {
            candidate = resolve(root, relative(actualRoot, candidate))
          }
          const linked = linkedDirectories.find(
            directory => within(directory, candidate),
          )
          if (!linked) {
            return null
          }
          const [actualDirectory, actualFile] = await Promise.all([
            realpath(linked),
            realpath(candidate),
          ])
          if (actualDirectory === actualRoot || !within(
            actualRoot,
            actualDirectory,
          )
          || !within(
            actualDirectory,
            actualFile,
          ) || !(await stat(actualFile))
            .isFile()) { return null }
          return { id: pathToFileURL(actualFile).href, external: true }
        }
        catch (error) {
          if (error.code === 'ENOENT' || error.code === 'ENOTDIR') {
            return null
          }
          throw error
        }
      },
    },
  }
}
