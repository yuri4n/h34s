// claude 2026-10-19: created: output-file resolution shared by scalaDevModules and scalaPrerenderStub, moved unchanged from scala-dev-modules.mjs
import { realpath, stat } from 'node:fs/promises'
import { dirname, extname, isAbsolute, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

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

// Returns `resolveOutput(source, importer)`, which answers the real path of the linked
// JavaScript file that an import names, or null when the import does not name an existing
// `.js` file inside one of the configured output directories. Relative imports need a local
// importer; extensionless imports get `.js`. Symlinks may not leave the output directory, and
// the output directory may not leave the project root.
export function scalaOutputResolver(projectRoot, outputDirectories) {
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

  return async function resolveOutput(source, importer) {
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
      const linked = linkedDirectories.find(directory =>
        within(directory, candidate),
      )
      if (!linked) {
        return null
      }
      const [actualDirectory, actualFile] = await Promise.all([
        realpath(linked),
        realpath(candidate),
      ])
      if (
        actualDirectory === actualRoot
        || !within(actualRoot, actualDirectory)
        || !within(actualDirectory, actualFile)
        || !(await stat(actualFile)).isFile()
      ) {
        return null
      }
      return actualFile
    }
    catch (error) {
      if (error.code === 'ENOENT' || error.code === 'ENOTDIR') {
        return null
      }
      throw error
    }
  }
}
