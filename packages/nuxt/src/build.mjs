import { spawn, spawnSync } from 'node:child_process'
import { lstatSync, mkdirSync, realpathSync, rmSync, statSync, watch } from 'node:fs'
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path' // claude 2026-10-20: changed: dirname and join for sbtRoot (ADR-0009)
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

// claude 2026-10-20: created: the file and the written sbtRoot of each config that loadConfig returned, for messages (ADR-0009)
const loadedConfigs = new WeakMap()

/** Keep app-owned settings in one file shared by the CLI and Nuxt module. */
export function defineConfig(config) {
  if (!config || !Array.isArray(config.modules) || config.modules.length === 0)
    throw new TypeError('h34s config requires at least one Scala.js module')
  // claude 2026-10-20: created: the optional sbtRoot key (ADR-0009)
  if (
    config.sbtRoot !== undefined
    && (typeof config.sbtRoot !== 'string' || !config.sbtRoot || config.sbtRoot.includes('\0'))
  ) {
    throw new TypeError(
      'sbtRoot must be a nonempty path to the sbt build, relative to the config file, or omitted',
    )
  }
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
  const config = defineConfig((await import(pathToFileURL(path).href)).default)
  // claude 2026-10-20: changed: resolves sbtRoot against the config file's directory, default '.' (ADR-0009)
  const loaded = { ...config, sbtRoot: resolve(dirname(path), config.sbtRoot ?? '.') }
  loadedConfigs.set(loaded, { file: path, sbtRoot: config.sbtRoot })
  return loaded
}

// claude 2026-10-20: created: the sbt build directory, checked before sbt starts or any output is removed (ADR-0009)
/**
 * The directory where the CLI runs sbt: `config.sbtRoot` resolved against `root` (`loadConfig`
 * has already made it absolute, against the config file's directory). It must hold `build.sbt` or
 * a `project/` directory, the files that sbt looks for. The sbt launcher itself also needs
 * `project/build.properties` when `build.sbt` is missing.
 */
function sbtDirectory(root, config) {
  const directory = resolve(root, config.sbtRoot ?? '.')
  const found = statSync(directory, { throwIfNoEntry: false })
  let problem
  if (!found)
    problem = 'does not exist'
  else if (!found.isDirectory())
    problem = 'is not a directory'
  else if (
    !statSync(join(directory, 'build.sbt'), { throwIfNoEntry: false })?.isFile()
    && !statSync(join(directory, 'project'), { throwIfNoEntry: false })?.isDirectory()
  ) {
    problem = 'has no build.sbt and no project/ directory'
  }
  if (!problem)
    return directory
  const loaded = loadedConfigs.get(config)
  const written = loaded ? loaded.sbtRoot : config.sbtRoot
  const key = written !== undefined
    ? `sbtRoot '${written}' resolves to ${directory}, which`
    : loaded
      ? `sbtRoot is not set, so the sbt build is the config file's directory, ${directory}, which`
      : `sbtRoot is not set, so the sbt build is the app root, ${directory}, which`
  const fix = loaded
    ? `Set sbtRoot in ${loaded.file} to the directory of your sbt build, relative to that file, for example '..'.`
    : 'Set sbtRoot in the h34s config to the directory of your sbt build, relative to the app root, or load the config with loadConfig.'
  throw new Error(`${key} ${problem}. ${fix}`)
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
  const sbt = sbtDirectory(root, config) // claude 2026-10-20: created: the sbt build, checked before any output is removed (ADR-0009)
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
    cwd: sbt, // claude 2026-10-20: changed: runs sbt in the sbt build (ADR-0009)
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
  const sbt = sbtDirectory(root, config) // claude 2026-10-20: created: the sbt build (ADR-0009)
  const directories = outputPaths(root, config)
  const tasks = [...new Set(config.modules.map(module => `${module.project ? `${module.project}/` : ''}fastLinkJS`))]
  const windows = process.platform === 'win32'
  const child = start(windows ? 'sbt.bat' : 'sbt', ['-Dsbt.supershell=false', '-batch', `~;${tasks.join(';')}`], {
    cwd: sbt, // claude 2026-10-20: changed: runs sbt in the sbt build (ADR-0009)
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
