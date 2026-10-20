import { spawn, spawnSync } from 'node:child_process'
import { lstatSync, mkdirSync, realpathSync, rmSync, statSync, watch } from 'node:fs'
import { isAbsolute, relative, resolve, sep } from 'node:path'
import process from 'node:process'
import { pathToFileURL } from 'node:url'
import { writeRoutes } from './routes.mjs'

export { manifestFile, modelsFile, readRoutes, writeRoutes } from './routes.mjs' // claude 2026-10-20: changed: export modelsFile (ADR-0014)

function inside(parent, child) {
  const path = relative(parent, child)
  return (
    path !== ''
    && path !== '..'
    && !path.startsWith(`..${sep}`)
    && !isAbsolute(path)
  )
}

function relativePath(value, label) {
  if (
    typeof value !== 'string'
    || !value
    || isAbsolute(value)
    || value.includes('\\')
    || value.includes('\0')
    || value.includes(':')
    || value.split('/').some(part => !part || part === '.' || part === '..')
  ) {
    throw new TypeError(
      `${label} must be a nonempty relative path without traversal`,
    )
  }
  return value
}

/** Keep app-owned settings in one file shared by the CLI and Nuxt module. */
export function defineConfig(config) {
  if (!config || !Array.isArray(config.modules) || config.modules.length === 0)
    throw new TypeError('h34s config requires at least one Scala.js module')
  const outputs = []
  for (const module of config.modules) {
    if (
      !module
      || (module.project !== undefined
        && module.project !== ''
        && (typeof module.project !== 'string'
          || !/^[A-Z0-9][\w.-]*$/i.test(module.project)))
    ) {
      throw new TypeError(
        'project must be an sbt project identifier, or omitted for the root project',
      )
    }
    relativePath(module.output, 'output')
    if (
      module.output
        .split('/')
        .some(part => ['.git', 'node_modules'].includes(part))
    ) {
      throw new TypeError('output must be a disposable generated directory')
    }
    const directory = resolve('/', module.output)
    if (
      outputs.some(
        output =>
          output === directory
          || inside(output, directory)
          || inside(directory, output),
      )
    ) {
      throw new TypeError('Scala.js output directories must not overlap')
    }
    outputs.push(directory)
    // Optional: without entries, the linked output must contain at least one h34s handler.
    if (module.entries === undefined)
      continue
    if (!Array.isArray(module.entries) || module.entries.length === 0) {
      throw new TypeError(
        'entries must be a nonempty list of expected .js entries, or omitted',
      )
    }
    for (const entry of module.entries) {
      relativePath(entry, 'entry')
      if (!entry.endsWith('.js'))
        throw new TypeError('Scala.js entries must end in .js')
    }
  }
  return config
}

export async function loadConfig({
  root = process.cwd(),
  configFile = 'h34s.config.mjs',
} = {}) {
  const path = resolve(root, configFile)
  if (!statSync(path, { throwIfNoEntry: false })?.isFile())
    throw new Error(`Missing h34s config: ${path}`)
  return defineConfig((await import(pathToFileURL(path).href)).default)
}

function outputPaths(root, config) {
  // Validate every directory before removing any output. Reject symlinked
  // ancestors too: rm must never traverse a generated path into another tree.
  const actualRoot = realpathSync(root)
  return config.modules.map((module) => {
    let current = actualRoot
    for (const part of module.output.split('/')) {
      current = resolve(current, part)
      const file = lstatSync(current, { throwIfNoEntry: false })
      if (file && (!file.isDirectory() || file.isSymbolicLink())) {
        throw new Error(
          `Scala.js output must use real directories: ${module.output}`,
        )
      }
    }
    return current
  })
}

export function verifyScalaOutputs({ root = process.cwd(), config }) {
  defineConfig(config)
  const directories = outputPaths(root, config)
  for (const [index, module] of config.modules.entries()) {
    for (const entry of module.entries ?? []) {
      const output = resolve(directories[index], entry)
      const file = statSync(output, { throwIfNoEntry: false })
      if (
        !file?.isFile()
        || file.size === 0
        || !inside(directories[index], realpathSync(output))
      ) {
        throw new Error(
          `Scala linker did not produce ${module.output}/${entry}`,
        )
      }
    }
  }
}

export function linkScala({
  root = process.cwd(),
  config,
  mode = 'build',
  run = spawnSync,
} = {}) {
  if (!['build', 'dev', 'watch'].includes(mode))
    throw new TypeError('Scala link mode must be build, dev or watch')
  defineConfig(config)
  root = resolve(root)
  const directories = outputPaths(root, config)
  if (mode === 'build') {
    for (const directory of directories)
      rmSync(directory, { recursive: true, force: true })
  }
  const task = mode === 'build' ? 'fullLinkJS' : 'fastLinkJS'
  const tasks = [
    ...new Set(
      config.modules.map(
        module => `${module.project ? `${module.project}/` : ''}${task}`,
      ),
    ),
  ]
  const args = [
    '-Dsbt.supershell=false',
    '-batch',
    ...(mode === 'watch' ? [`~;${tasks.join(';')}`] : tasks),
  ]
  const windows = process.platform === 'win32'
  const result = run(windows ? 'sbt.bat' : 'sbt', args, {
    cwd: root,
    stdio: 'inherit',
    ...(windows ? { shell: true } : {}),
  })
  if (result.error)
    throw result.error
  if (result.signal || result.status !== 0) {
    throw new Error(
      `Scala ${mode} linking failed (${result.signal || `exit ${result.status}`})`,
    )
  }
  verifyScalaOutputs({ root, config })
}

export function buildScala(options) {
  return linkScala({ ...options, mode: 'build' })
}

/**
 * Keeps sbt linking on source changes, and writes the route manifests and declarations again
 * after each link. Resolves when sbt exits.
 */
export function watchScala({
  root = process.cwd(),
  config,
  start = spawn,
  collect = writeRoutes,
  quiet = 300,
  log = console,
} = {}) {
  defineConfig(config)
  root = resolve(root)
  const directories = outputPaths(root, config)
  const tasks = [...new Set(config.modules.map(module => `${module.project ? `${module.project}/` : ''}fastLinkJS`))]
  const windows = process.platform === 'win32'
  const child = start(windows ? 'sbt.bat' : 'sbt', ['-Dsbt.supershell=false', '-batch', `~;${tasks.join(';')}`], {
    cwd: root,
    stdio: 'inherit',
    ...(windows ? { shell: true } : {}),
  })
  let timer
  let running = Promise.resolve()
  function scheduled() {
    clearTimeout(timer)
    // The linker writes several files: collect once they are all written.
    timer = setTimeout(() => {
      running = running.then(() => collect({ root, config })).then(
        () => log.info('[h34s] Routes and declarations updated'),
        error => log.error(`[h34s] ${error.message}`),
      )
    }, quiet)
  }
  const watchers = directories.map((directory) => {
    mkdirSync(directory, { recursive: true })
    return watch(directory, (_event, file) => {
      if (typeof file === 'string' && file.endsWith('.js'))
        scheduled()
    })
  })
  return new Promise((resolvePromise, reject) => {
    function stop() {
      clearTimeout(timer)
      for (const watcher of watchers)
        watcher.close()
    }
    child.once('error', (error) => {
      stop()
      reject(error)
    })
    child.once('exit', (code, signal) => {
      stop()
      if (signal || code !== 0)
        reject(new Error(`Scala watch linking stopped (${signal || `exit ${code}`})`))
      else
        resolvePromise()
    })
  })
}
