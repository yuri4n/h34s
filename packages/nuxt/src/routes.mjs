import { fork } from 'node:child_process'
import { mkdirSync, readFileSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { basename, resolve } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

/** The route manifest that `h34s-scala` writes into each Scala.js output directory. */
export const manifestFile = 'h34s-routes.json'
// claude 2026-10-20: created: the models file of each output directory (ADR-0014)
/** The declarations of the named models that `h34s-scala` writes into each output directory. */
export const modelsFile = 'h34s-models.d.ts'
const manifestVersion = 1
const recordVersion = 1
const methods = new Set(['get', 'head', 'post', 'put', 'patch', 'delete', 'options'])
const collector = fileURLToPath(new URL('./collect-routes.mjs', import.meta.url))

/** Why `path` cannot be a Nitro route, or undefined. The same rules as Scala's `Route`. */
function routeProblem(path) {
  if (typeof path !== 'string' || !path)
    return 'a route path must be a nonempty string'
  if (!path.startsWith('/'))
    return `a route path must start with '/': ${path}`
  if (/[\s\p{Cc}?#\\]/u.test(path))
    return `a route path must not contain spaces, control characters, '?', '#' or '\\': ${path}`
  if (path.length > 1 && path.split('/').slice(1).includes(''))
    return `a route path must not contain empty segments: ${path}`
  return undefined
}

function describeRoute(route) {
  return `${route.method ? route.method.toUpperCase() : '*'} ${route.route}`
}

/** Imports the linked modules of `directory` in a child Node process and returns their records. */
export function loadRecords(directory, { execArgv = ['--enable-source-maps', '--no-deprecation'] } = {}) {
  if (!statSync(directory, { throwIfNoEntry: false })?.isDirectory())
    return Promise.resolve([])
  return new Promise((resolvePromise, reject) => {
    const child = fork(collector, [directory], {
      cwd: directory,
      execArgv,
      stdio: ['ignore', 'inherit', 'inherit', 'ipc'],
    })
    let answer
    child.once('message', (message) => {
      answer = message
    })
    child.once('error', reject)
    child.once('exit', (code, signal) => {
      if (answer?.error)
        reject(new Error(`Could not load the linked Scala.js module ${answer.error}`))
      else if (answer)
        resolvePromise(answer.modules)
      else
        reject(new Error(`The h34s route collector stopped (${signal || `exit ${code}`}) in ${directory}`))
    })
  })
}

// claude 2026-10-20: created: checks the shape of a record's named types (ADR-0014)
/** Why `named` is not the named types that h34s records, or undefined. */
function namedProblem(named) {
  const names = value => Array.isArray(value) && value.every(name => typeof name === 'string')
  if (typeof named !== 'object' || named === null || typeof named.success !== 'string' || !names(named.uses) || !Array.isArray(named.models))
    return 'its named types are not { success, uses, models }'
  for (const model of named.models) {
    if (typeof model?.name !== 'string' || !/^[A-Z_$][\w$]*$/i.test(model.name) || typeof model.type !== 'string' || !names(model.uses))
      return `its model ${JSON.stringify(model?.name)} is not { name, type, uses, scala? }`
    if (model.scala !== undefined && typeof model.scala !== 'string')
      return `its model ${model.name} has a Scala name that is not a string`
  }
  return undefined
}

/** Checks the records of one output directory and returns its routes and declarations. */
export function readRecords(output, records) {
  const routes = []
  const declarations = []
  for (const record of records) {
    if (record.version !== recordVersion) {
      throw new Error(
        `${output}/${record.file} was linked with an h34s version this tool does not support (record version ${record.version})`,
      )
    }
    // claude 2026-10-20: changed: a module's declaration must not replace the models file, and named types must have their shape (ADR-0014)
    if (typeof record.success === 'string' && typeof record.errors === 'string') {
      if (declarationFile(record.file) === modelsFile)
        throw new Error(`${output}/${record.file}: the module ID ${record.file.replace(/\.js$/, '')} is reserved for the models file; give the Scala.js module another ID`)
      const problem = record.named === undefined ? undefined : namedProblem(record.named)
      if (problem)
        throw new Error(`${output}/${record.file}: ${problem}; link it with the h34s version of this tool`)
      declarations.push(record)
    }
    if (record.route === undefined)
      continue
    const problem = routeProblem(record.route)
    if (problem)
      throw new Error(`${output}/${record.file}: ${problem}`)
    if (record.method !== undefined && !methods.has(record.method))
      throw new Error(`${output}/${record.file}: unsupported route method ${record.method}`)
    routes.push({ module: record.file, route: record.route, ...(record.method ? { method: record.method } : {}) })
  }
  return { routes, declarations }
}

// claude 2026-10-20: changed: imports the models that the success type names (ADR-0014)
/**
 * The TypeScript declaration of one linked module: Nitro types `$fetch` from its default export.
 * `record.imports` lists the models of `h34s-models.d.ts` that `record.success` refers to.
 */
export function declaration(record) {
  const mount = record.route ? ` It is mounted at ${describeRoute(record)}.` : ''
  const models = record.imports?.length ? `import type { ${record.imports.join(', ')} } from './${modelsFile.replace(/\.d\.ts$/, '')}'\n` : ''
  return `// Generated by h34s-scala from the Scala.js module ${record.file}.${mount} Do not edit.
import type { EventHandler, EventHandlerRequest } from 'h3'
${models}
/** The body of a successful response. */
export type SuccessBody = ${record.success}

/** The body of an error response. \`debug\` appears only in development or debug mode. */
export type ErrorBody = ${record.errors}

declare const handler: EventHandler<EventHandlerRequest, Promise<SuccessBody>>
export default handler
`
}

function declarationFile(file) {
  return file.replace(/\.js$/, '.d.ts')
}

// claude 2026-10-20: created: where a model is used, for messages (ADR-0014)
function usedBy(output, record) {
  return record.route ? describeRoute(record) : `${output}/${record.file}`
}

// claude 2026-10-20: created: the clash message names every Scala type and the routes that use it (ADR-0014)
function clash(scope, name, variants) {
  const described = variants.map(variant =>
    `${variant.scala ?? 'a hand-written WireType.Named'} (used by ${[...variant.users].join(', ')})`,
  )
  const listed = described.length === 2 ? described.join(' and ') : `${described.slice(0, -1).join(', ')} and ${described.at(-1)}`
  return new Error(
    `${scope}: two Scala types have the TypeScript name ${name} and different structures: ${listed}. `
    + 'Rename one of the Scala types, so that each TypeScript name means one structure.',
  )
}

// claude 2026-10-20: created: merges the models of one output directory (ADR-0014)
/**
 * Merges the named models of one output's declarations. A model with the same name and the same
 * structure is declared once. The same name with different structures fails the generation, unless
 * every one of them comes from the same Scala type: that is a generic type (ZIO's `TypeId` has no
 * type arguments), and it is written out where it is used, with every model that uses it and every
 * route whose type refers to one of them. Gives the models to declare in dependency order and the
 * declarations with the `success` type and the `imports` to write.
 */
export function mergeModels(output, declarations) {
  const byName = new Map()
  for (const record of declarations) {
    for (const model of record.named?.models ?? []) {
      const variants = byName.get(model.name) ?? []
      let variant = variants.find(other => other.type === model.type)
      if (!variant) {
        variant = { name: model.name, type: model.type, scala: model.scala, uses: model.uses, users: new Set() }
        variants.push(variant)
        byName.set(model.name, variants)
      }
      variant.users.add(usedBy(output, record))
    }
  }
  const written = new Set()
  for (const [name, variants] of byName) {
    if (variants.length === 1)
      continue
    if (variants.every(variant => typeof variant.scala === 'string' && variant.scala === variants[0].scala))
      written.add(name)
    else
      throw clash(output, name, variants)
  }
  // A model that uses a written-out model is written out too, until nothing changes.
  for (let grown = written.size > 0; grown;) {
    grown = false
    for (const [name, [model]] of byName) {
      if (!written.has(name) && model.uses.some(used => written.has(used))) {
        written.add(name)
        grown = true
      }
    }
  }
  // Each declaration lists its models in dependency order, so the first appearance keeps that order.
  const models = [...byName.entries()].filter(([name]) => !written.has(name)).map(([, [model]]) => model)
  const typed = declarations.map((record) => {
    const named = record.named
    if (!named || named.models.some(model => written.has(model.name)))
      return { ...record, imports: [] }
    return { ...record, success: named.success, imports: named.uses }
  })
  return { models, declarations: typed }
}

// claude 2026-10-20: created: the content of the models file (ADR-0014)
/** The models file of one output directory: one `export type` per model, in dependency order. */
export function modelsDeclaration(models) {
  const header = `// Generated by h34s-scala from the Scala.js modules of this directory. Do not edit.
// The models that the routes of this directory return. Import them in your app:
// import type { Name } from '#h34s/models'
`
  if (models.length === 0)
    return `${header}\nexport {}\n`
  return header + models.map(model =>
    `\n${model.scala ? `/** The Scala type \`${model.scala.replaceAll('*/', '*\\/')}\`. */\n` : ''}export type ${model.name} = ${model.type}\n`,
  ).join('')
}

function writeAtomically(path, content) {
  const next = `${path}.${process.pid}.next`
  writeFileSync(next, content)
  renameSync(next, path)
}

function readManifestFile(path) {
  const file = statSync(path, { throwIfNoEntry: false })
  if (!file?.isFile())
    return undefined
  const manifest = JSON.parse(readFileSync(path, 'utf8'))
  if (manifest?.version !== manifestVersion || !Array.isArray(manifest.routes))
    throw new Error(`${path} is not an h34s route manifest of version ${manifestVersion}; link the Scala.js modules again`)
  return manifest
}

// claude 2026-10-19: changed: checks every output before it writes any file, so a failure keeps all manifests as they were
/**
 * Loads and checks the linked modules of every configured output, then writes a `.d.ts` file
 * next to each module whose default export is an h34s handler, and the route manifest of each
 * output. A module without `entries` must contain at least one h34s handler. When any output
 * fails, no file of any output changes.
 */
export async function writeRoutes({ root = process.cwd(), config, load = loadRecords }) {
  const seen = new Map()
  const outputs = []
  const exported = new Map() // claude 2026-10-20: created: the models of every output, which #h34s/models exports together (ADR-0014)
  for (const module of config.modules) {
    const directory = resolve(root, module.output)
    const read = readRecords(module.output, await load(directory))
    const { routes } = read
    // claude 2026-10-20: created: merges the models of the output, then checks them against the other outputs (ADR-0014)
    const { models, declarations } = mergeModels(module.output, read.declarations)
    for (const model of models) {
      const users = [...model.users].map(user => user.startsWith(`${module.output}/`) ? user : `${module.output}: ${user}`)
      const previous = exported.get(model.name)
      if (previous && previous.type !== model.type)
        throw clash('#h34s/models exports the models of every output', model.name, [previous, { ...model, users }])
      if (!previous)
        exported.set(model.name, { ...model, users })
    }
    if (!module.entries && declarations.length === 0) {
      throw new Error(
        `Scala.js output ${module.output} has no h34s handlers. Export a handler as a module default, or list entries in the h34s config`,
      )
    }
    for (const route of routes) {
      const key = describeRoute(route)
      const previous = seen.get(key)
      if (previous)
        throw new Error(`Two Scala.js handlers mount ${key}: ${previous} and ${module.output}/${route.module}`)
      seen.set(key, `${module.output}/${route.module}`)
    }
    const manifestPath = resolve(directory, manifestFile)
    outputs.push({ module, directory, manifestPath, previous: readManifestFile(manifestPath), routes, declarations, models })
  }
  // Every output is valid: only now replace its files. Nuxt never sees a mix of old and new routes.
  const results = []
  for (const { module, directory, manifestPath, previous, routes, declarations, models } of outputs) {
    mkdirSync(directory, { recursive: true })
    // claude 2026-10-20: created: the models file, also when it is empty, so that #h34s/models resolves (ADR-0014)
    writeAtomically(resolve(directory, modelsFile), modelsDeclaration(models))
    const written = new Set()
    for (const record of declarations) {
      const file = declarationFile(record.file)
      writeAtomically(resolve(directory, file), declaration(record))
      written.add(file)
    }
    // Remove declarations of modules that no longer have an h34s handler.
    for (const file of previous?.declarations ?? []) {
      if (typeof file === 'string' && !written.has(file) && basename(file) === file && file.endsWith('.d.ts'))
        rmSync(resolve(directory, file), { force: true })
    }
    // claude 2026-10-20: changed: the manifest lists the models file and its model names (ADR-0014)
    const manifest = {
      version: manifestVersion,
      routes,
      declarations: [...written].sort(),
      models: { file: modelsFile, names: models.map(model => model.name) },
    }
    writeAtomically(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`)
    results.push({ output: module.output, ...manifest })
  }
  return results
}

/**
 * The Nitro handlers of every configured output, from its route manifest. Outputs without a
 * manifest (not linked yet) are listed in `missing`.
 */
export function readRoutes({ root = process.cwd(), config }) {
  const handlers = []
  const missing = []
  for (const module of config.modules) {
    const directory = resolve(root, module.output)
    const manifest = readManifestFile(resolve(directory, manifestFile))
    if (!manifest) {
      missing.push(module.output)
      continue
    }
    for (const route of manifest.routes) {
      const problem = routeProblem(route?.route)
      if (problem)
        throw new Error(`${module.output}/${manifestFile}: ${problem}`)
      if (route.method !== undefined && !methods.has(route.method))
        throw new Error(`${module.output}/${manifestFile}: unsupported route method ${route.method}`)
      if (typeof route.module !== 'string' || basename(route.module) !== route.module || !route.module.endsWith('.js'))
        throw new Error(`${module.output}/${manifestFile}: invalid module ${route.module}`)
      handlers.push({
        route: route.route,
        ...(route.method ? { method: route.method } : {}),
        lazy: true,
        handler: resolve(directory, route.module),
      })
    }
  }
  return { handlers, missing }
}
