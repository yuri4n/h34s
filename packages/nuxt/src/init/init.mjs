// `h34s-scala init`: sets up h34s once per project (ADR-0009). It detects the adoption path from the
// files present, builds the complete plan, checks every target before it writes anything, creates
// missing files only, and never overwrites a file. Its only edits are appends to .gitignore and
// .npmrc, the merge into package.json and the text edit of nuxt.config. It installs nothing and
// runs no sbt, and it reads no token: the files name environment variables.
import { mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { basename, dirname, join, relative, resolve, sep } from 'node:path'
import process from 'node:process'
import { parseArgs } from 'node:util'
import { packageVersion } from '../version.mjs'
import { addModule } from './nuxt-config-edit.mjs'
import * as appVue from './templates/app-vue.mjs'
import * as buildProperties from './templates/build-properties.mjs'
import * as gitignore from './templates/gitignore.mjs'
import * as h34sConfig from './templates/h34s-config.mjs'
import * as helloScala from './templates/hello-scala.mjs'
import * as jvmopts from './templates/jvmopts.mjs'
import * as npmrc from './templates/npmrc.mjs'
import * as nuxtConfig from './templates/nuxt-config.mjs'
import * as packageJson from './templates/package-json.mjs'
import * as pluginsSbt from './templates/plugins-sbt.mjs'
import * as pnpmWorkspace from './templates/pnpm-workspace.mjs'
import { stripRegions } from './templates/regions.mjs'
import * as sbtBuild from './templates/sbt-build.mjs'
import * as tsconfig from './templates/tsconfig.mjs'

// claude 2026-10-20: created: the names of decision 9 (ADR-0009)
/** The sbt project ID and the directory of the Scala sources (ADR-0009, decision 9). */
const project = 'server'
const scalaDir = 'scala-server'
// claude 2026-10-20: created: the Nuxt app directory of layout B (ADR-0009, decision 7)
/** The Nuxt app directory that init creates below an existing sbt build (layout B). */
const webDir = 'web'
// claude 2026-10-20: created: the config names that mark a Nuxt app, and the names a Scala package must not take (ADR-0009)
const nuxtConfigNames = ['nuxt.config.ts', 'nuxt.config.mts', 'nuxt.config.cts', 'nuxt.config.js', 'nuxt.config.mjs', 'nuxt.config.cjs']
const scalaKeywords = new Set(['abstract', 'case', 'catch', 'class', 'def', 'do', 'else', 'enum', 'export', 'extends', 'false', 'final', 'finally', 'for', 'given', 'if', 'implicit', 'import', 'lazy', 'match', 'new', 'null', 'object', 'override', 'package', 'private', 'protected', 'return', 'sealed', 'super', 'then', 'throw', 'trait', 'true', 'try', 'type', 'val', 'var', 'while', 'with', 'yield'])
/** Top-level packages that a project package must not shadow. */
const reservedPackages = new Set(['scala', 'java', 'javax', 'zio', 'kyo', 'dev', 'org', 'com', 'js'])

// claude 2026-10-20: created: file checks (ADR-0009)
const exists = path => statSync(path, { throwIfNoEntry: false }) !== undefined
const isFile = path => statSync(path, { throwIfNoEntry: false })?.isFile() === true
const isDirectory = path => statSync(path, { throwIfNoEntry: false })?.isDirectory() === true
const read = path => readFileSync(path, 'utf8')

// claude 2026-10-20: created: a Scala package name from a directory name (ADR-0009)
/** The directory name in lower case, letters and digits only, or `app` when that is not a usable package. */
export function scalaPackageName(directory) {
  const name = basename(directory).toLowerCase().replace(/[^a-z0-9]/g, '')
  return !name || /^\d/.test(name) || scalaKeywords.has(name) || reservedPackages.has(name) ? 'app' : name
}

// claude 2026-10-20: created: an npm package name from a directory name (ADR-0009)
/** The directory name as a valid npm package name, or `h34s-app`. */
export function npmPackageName(directory) {
  const name = basename(directory)
    .toLowerCase()
    .replace(/[^a-z0-9._~-]+/g, '-')
    .replace(/^[._-]+/, '')
    .replace(/-+$/, '')
    .slice(0, 214)
  return name || 'h34s-app'
}

// claude 2026-10-20: created: the Nuxt config file of a directory, if any (ADR-0009)
function nuxtConfigFile(directory) {
  return nuxtConfigNames.map(name => join(directory, name)).find(isFile)
}

// claude 2026-10-20: created: a Nuxt app is a directory with a nuxt.config or a package.json that lists nuxt (ADR-0009)
function isNuxtApp(directory) {
  if (nuxtConfigFile(directory))
    return true
  const manifest = join(directory, 'package.json')
  if (!isFile(manifest))
    return false
  try {
    const value = JSON.parse(read(manifest))
    return Boolean(value?.dependencies?.nuxt || value?.devDependencies?.nuxt)
  }
  catch {
    return false
  }
}

// claude 2026-10-20: created: the entries that a new project's directory may already hold, such as a fresh git clone (review S4)
/** Repository files that do not make a directory a project: `git init`, or a clone with a README and a license. */
const repositoryEntry = /^(?:\.git|\.gitignore|\.gitattributes|\.editorconfig|\.DS_Store|(?:readme|license|licence|copying)(?:\..*)?)$/i

// claude 2026-10-20: created: a directory that holds no project yet (review S4)
/** Whether `directory` is missing, empty, or holds only repository files. */
function holdsNoProject(directory) {
  return !exists(directory) || readdirSync(directory).every(name => repositoryEntry.test(name))
}

// claude 2026-10-20: changed: a Nuxt app below an sbt build is layout B, and a directory with another project stops init (review S1, S4)
/**
 * Detects the adoption path for `target`, the `[directory]` argument resolved against the
 * current directory. The first matching rule wins:
 *
 * 1. `target` is a Nuxt app (a `nuxt.config.*`, or a `package.json` that lists `nuxt`). With a
 *    `build.sbt` there too: path 2, layout A, and init writes `h34s.sbt` next to it. Else, when
 *    its parent has a `build.sbt`: path 3, layout B, with the Nuxt app in `target` (`init web`).
 *    Else: path 2, layout A at `target`.
 * 2. `target` has a `build.sbt`: path 3, layout B, with the Nuxt app in `target/web`. When that
 *    directory is already a Nuxt app, init adds h34s to it.
 * 3. `target` holds no project yet (missing, empty, or only repository files such as `.git` and
 *    a README) and its parent has a `build.sbt`: path 3, layout B, with the Nuxt app in `target`.
 * 4. `target` holds no project yet: path 1, a new project in layout A at `target`.
 * 5. Otherwise `target` holds another project, or is not a project directory: path `null`, and
 *    init stops without writing.
 *
 * `h34s.config.mjs` in `target`, in the app directory or in the sbt build means "already set up".
 */
export function detect(target) {
  if (exists(target) && !isDirectory(target))
    throw new Error(`${target} is not a directory`)
  const parentBuild = isFile(join(dirname(target), 'build.sbt'))
  if (exists(target) && isNuxtApp(target)) {
    if (isFile(join(target, 'build.sbt')))
      return { path: 2, layout: 'A', app: target, sbt: target, nuxt: 'existing', build: 'existing' }
    if (parentBuild)
      return { path: 3, layout: 'B', app: target, sbt: dirname(target), nuxt: 'existing', build: 'existing' }
    return { path: 2, layout: 'A', app: target, sbt: target, nuxt: 'existing', build: 'new' }
  }
  if (isFile(join(target, 'build.sbt'))) {
    const app = join(target, webDir)
    return { path: 3, layout: 'B', app, sbt: target, nuxt: isDirectory(app) && isNuxtApp(app) ? 'existing' : 'new', build: 'existing' }
  }
  const fresh = holdsNoProject(target)
  if (fresh && parentBuild)
    return { path: 3, layout: 'B', app: target, sbt: dirname(target), nuxt: 'new', build: 'existing' }
  if (fresh)
    return { path: 1, layout: 'A', app: target, sbt: target, nuxt: 'new', build: 'new' }
  return { path: null, layout: null, app: target, sbt: target, nuxt: null, build: null }
}

// claude 2026-10-20: created: the text of every root .sbt and project/*.sbt|*.scala file of a build (ADR-0009)
function buildSources(sbt) {
  const files = []
  for (const directory of [sbt, join(sbt, 'project')]) {
    if (!isDirectory(directory))
      continue
    for (const name of readdirSync(directory)) {
      const path = join(directory, name)
      if ((name.endsWith('.sbt') || (directory !== sbt && name.endsWith('.scala'))) && isFile(path))
        files.push({ path, text: read(path) })
    }
  }
  return files
}

// claude 2026-10-20: created: the lines of a list that a text file lacks, compared without leading or trailing slashes (ADR-0009)
function missingLines(text, lines) {
  const normal = line => line.trim().replace(/^\/+|\/+$/g, '')
  const present = new Set(text.split(/\r?\n/).map(normal))
  return lines.filter(line => !present.has(normal(line)))
}

// claude 2026-10-20: created: the line ending of a text file, CRLF when it has one (review N3)
const lineEnding = text => text.includes('\r\n') ? '\r\n' : '\n'

// claude 2026-10-20: changed: keeps the file's line ending (review N3)
/** `text` with `lines` appended after a blank line and a heading comment. */
function appended(text, heading, lines) {
  const eol = lineEnding(text)
  const separator = text === '' || text.endsWith('\n') ? '' : eol
  return `${text}${separator}${text === '' ? '' : eol}${[heading, ...lines].join(eol)}${eol}`
}

// claude 2026-10-20: created: the version spec of a dependency, without range operators (ADR-0009)
function bareVersion(spec) {
  return String(spec).replace(/^[\^~=v\s]+/, '')
}

// claude 2026-10-20: changed: also checks the sbt build's h34s.config.mjs, stops in a directory that holds another project, and adds the sbt resolver and credentials one by one (review S4, S5, P1)
/**
 * Builds the plan for `directory` (relative to `cwd`): every file to create with its content, every
 * edit with the text it expects and the text it writes, the conflicts that stop init, the steps
 * the author must do by hand, and warnings. It reads files and writes nothing.
 */
export function planInit({ directory = '.', api = 'kyo', cwd = process.cwd(), version = packageVersion() } = {}) {
  if (api !== 'kyo' && api !== 'zio')
    throw new TypeError(`api must be 'kyo' or 'zio', got ${api}`)
  const target = resolve(cwd, directory)
  const found = detect(target)
  const { app, sbt } = found
  const plan = { ...found, api, version, cwd, actions: [], conflicts: [], manual: [], warnings: [], kept: [] }
  const configPath = join(app, 'h34s.config.mjs')
  // One-shot: an h34s.config.mjs in the directory, where the plan would write one, or in the sbt
  // build that the plan would add to, as layout A (decision 4).
  const setUp = [join(target, 'h34s.config.mjs'), configPath, join(sbt, 'h34s.config.mjs')].find(isFile)
  if (setUp) {
    plan.alreadySetUp = setUp
    return plan
  }
  const shown = path => relative(cwd, path) || '.'
  if (found.path === null) {
    plan.conflicts.push(`${shown(target)} is not empty and has no nuxt.config.*, no package.json that lists nuxt and no build.sbt, so init does not know which project to add h34s to. Run init in the root of your Nuxt app or sbt build, or give it a new directory for a new project: h34s-scala init my-app`)
    return plan
  }
  const create = (path, content) => {
    if (exists(path))
      plan.conflicts.push(`${shown(path)} exists; init creates it and never overwrites a file`)
    else
      plan.actions.push({ kind: 'create', path, content: stripRegions(content) })
  }
  const edit = (path, before, content, what) => {
    if (content !== before)
      plan.actions.push({ kind: 'edit', path, before, content, what })
  }
  const appDir = relative(sbt, app).split(sep).join('/')
  const sbtRoot = relative(app, sbt).split(sep).join('/') || '.'
  const params = {
    api,
    version,
    project,
    scalaDir,
    appDir,
    sbtRoot,
    scalaPackage: scalaPackageName(sbt),
    npmName: found.layout === 'B' && found.nuxt === 'new'
      ? npmPackageName(`${basename(sbt)}-${basename(app)}`)
      : npmPackageName(app),
  }

  // The Nuxt half. h34s.config.mjs comes first: the module stops Nuxt without it (memo, 2.6).
  create(configPath, h34sConfig.render(params))
  const output = join(app, 'server/.scala')
  if (exists(output) && (!isDirectory(output) || readdirSync(output).length > 0))
    plan.conflicts.push(`${shown(output)} exists and is not empty; h34s links into it and every production build removes it`)
  for (const name of ['hello.ts', 'hello.get.ts', 'hello.js', 'hello.get.js', 'hello.mjs', 'hello.get.mjs']) {
    const route = join(app, 'server/api', name)
    if (exists(route))
      plan.conflicts.push(`${shown(route)} exists; the new Scala handler serves GET /api/hello`)
  }
  if (found.nuxt === 'new') {
    create(join(app, 'package.json'), packageJson.render(params))
    create(join(app, 'pnpm-workspace.yaml'), pnpmWorkspace.render())
    create(join(app, 'nuxt.config.ts'), nuxtConfig.render())
    create(join(app, 'tsconfig.json'), tsconfig.render())
    create(join(app, 'app/app.vue'), appVue.render())
  }
  else {
    planPackageJson(plan, join(app, 'package.json'), params, { create, edit, shown })
    planNuxtConfig(plan, app, { create, edit, shown })
  }
  planAppend(plan, join(app, '.npmrc'), npmrc.render(), { create, edit, shown })
  // An existing app keeps its own Nuxt entries: init appends only what h34s adds.
  const ignored = gitignore.entries({ sbtAtApp: found.layout === 'A', nuxt: found.nuxt === 'new' })
  const gitignorePath = join(app, '.gitignore')
  if (isFile(gitignorePath)) {
    const before = read(gitignorePath)
    const lines = missingLines(before, ignored)
    if (lines.length > 0)
      edit(gitignorePath, before, appended(before, '# Added by h34s-scala init', lines), `adds ${lines.join(', ')}`)
  }
  else {
    create(gitignorePath, gitignore.render({ sbtAtApp: found.layout === 'A' }))
  }

  // The sbt half. The resolver and the credentials are two stanzas: h34s.sbt adds the ones the build lacks.
  const sources = buildSources(sbt)
  const resolver = !sources.some(source => source.text.includes(sbtBuild.registryHost))
  const credentials = !sources.some(source => sbtBuild.credentialsPattern.test(source.text))
  if (!resolver)
    plan.kept.push('the h34s resolver that your sbt build already has')
  if (!credentials)
    plan.kept.push('the GitHub Packages credentials that your sbt build already has')
  for (const source of sources) {
    if (new RegExp(`\\bval\\s+${project}\\b`).test(source.text))
      plan.conflicts.push(`${shown(source.path)} already defines an sbt value named ${project}; init adds the sbt project ${project}`)
    if (source.text.includes(`file("${scalaDir}")`))
      plan.conflicts.push(`${shown(source.path)} already uses the directory ${scalaDir}; init puts the Scala sources there`)
  }
  const sbtFile = found.build === 'existing' ? 'h34s.sbt' : 'build.sbt'
  create(join(sbt, sbtFile), sbtBuild.render({ ...params, sbtFile, resolver, credentials }))
  planProject(plan, sbt, sources, { create, shown })
  if (!exists(join(sbt, '.jvmopts')))
    create(join(sbt, '.jvmopts'), jvmopts.render())
  else
    plan.kept.push(`${shown(join(sbt, '.jvmopts'))} as it is (h34s builds use -Xss4m and -Xmx4g)`)
  create(join(sbt, scalaDir, 'src/main/scala', params.scalaPackage, 'Hello.scala'), helloScala.render(params))

  // Creates first, then edits; the nuxt.config edit last, after h34s.config.mjs exists.
  const rank = action => action.kind === 'create' ? 0 : action.path === plan.nuxtConfigPath ? 2 : 1
  plan.actions.sort((a, b) => rank(a) - rank(b))
  return plan
}

// claude 2026-10-20: changed: the package.json merge keeps the line ending; a same-name script with another value is a conflict, except dev and build, which init leaves to the author (ADR-0009, review N3)
function planPackageJson(plan, path, params, { create, edit, shown }) {
  if (!isFile(path)) {
    create(path, packageJson.render(params))
    return
  }
  const before = read(path)
  let value
  try {
    value = JSON.parse(before)
  }
  catch {
    plan.conflicts.push(`${shown(path)} is not valid JSON; init merges its scripts and dependencies into it`)
    return
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    plan.conflicts.push(`${shown(path)} is not a JSON object`)
    return
  }
  const next = structuredClone(value)
  const changes = []
  next.scripts ??= {}
  const added = []
  for (const [name, script] of Object.entries(packageJson.scripts)) {
    const current = next.scripts[name]
    if (current === undefined) {
      next.scripts[name] = script
      added.push(name)
    }
    else if (current !== script) {
      if (name === 'dev' || name === 'build') {
        plan.manual.push(`In ${shown(path)}, set the script "${name}" to "${script}"${name === 'build' ? ': a production build must link the Scala.js first, so that it never packages stale output' : ', which also turns on source maps for Scala stack traces'}. init keeps your "${current}".`)
      }
      else {
        plan.conflicts.push(`${shown(path)} has the script "${name}" set to "${current}"; init would add "${script}"`)
      }
    }
  }
  if (added.length > 0)
    changes.push(`adds the scripts ${added.join(', ')}`)
  const listed = next.dependencies?.['@yuri4n/h34s-nuxt'] ?? next.devDependencies?.['@yuri4n/h34s-nuxt']
  if (listed === undefined) {
    next.devDependencies = { ...next.devDependencies, '@yuri4n/h34s-nuxt': params.version }
    changes.push(`adds @yuri4n/h34s-nuxt ${params.version} to devDependencies`)
  }
  else if (bareVersion(listed) !== params.version) {
    plan.warnings.push(`${shown(path)} lists @yuri4n/h34s-nuxt ${listed}, and the sbt build uses the Scala libraries ${params.version}. Use the same version for both.`)
  }
  const h3 = next.dependencies?.h3 ?? next.devDependencies?.h3
  if (h3 === undefined) {
    next.dependencies = { ...next.dependencies, h3: packageJson.h3Version }
    changes.push(`adds h3 ${packageJson.h3Version} to dependencies`)
  }
  else if (bareVersion(h3) !== packageJson.h3Version) {
    plan.warnings.push(`${shown(path)} lists h3 ${h3}; h34s is built and tested with h3 ${packageJson.h3Version}.`)
  }
  const nuxt = next.dependencies?.nuxt ?? next.devDependencies?.nuxt
  if (nuxt !== undefined && !['4.3.', '4.4.'].some(prefix => bareVersion(nuxt).startsWith(prefix)))
    plan.warnings.push(`${shown(path)} lists nuxt ${nuxt}; h34s is tested with Nuxt 4.3.1 and 4.4.2.`)
  if (changes.length === 0)
    return
  // The content, the key order, the indentation and the line ending stay; other layout is JSON's.
  const indent = /^[ \t]+/m.exec(before)?.[0] ?? '  '
  const eol = lineEnding(before)
  edit(path, before, `${JSON.stringify(next, null, indent).replaceAll('\n', eol)}${eol}`, changes.join('; '))
}

// claude 2026-10-20: created: the nuxt.config text edit, or the printed line (ADR-0009, decision 2)
function planNuxtConfig(plan, app, { create, edit, shown }) {
  const path = nuxtConfigFile(app)
  if (!path) {
    create(join(app, 'nuxt.config.ts'), nuxtConfig.render())
    return
  }
  plan.nuxtConfigPath = path
  const before = read(path)
  const preset = /\bpreset\s*:\s*['"`]([^'"`]+)['"`]/.exec(before)?.[1]
  if (preset && !preset.startsWith('node'))
    plan.warnings.push(`${shown(path)} sets the Nitro preset '${preset}'; h34s supports Node presets only, such as 'node-server'.`)
  if (before.includes(nuxtConfig.moduleName)) {
    plan.kept.push(`${shown(path)}, which already lists ${nuxtConfig.moduleName}`)
    return
  }
  const after = addModule(before, nuxtConfig.moduleName)
  if (after === null) {
    plan.manual.push(`Add '${nuxtConfig.moduleName}' to the modules in ${shown(path)}. init edits only a file with one defineNuxtConfig({ ... }) whose modules is an array literal or missing:\n      modules: ['${nuxtConfig.moduleName}'],`)
    return
  }
  edit(path, before, after, `adds '${nuxtConfig.moduleName}' to modules`)
}

// claude 2026-10-20: changed: the .npmrc lines, appended when missing; a registry URL with a trailing slash is the same registry; an existing token line stays as it is and is never printed (ADR-0009, decision 10, review N2)
function planAppend(plan, path, content, { create, edit, shown }) {
  if (!isFile(path)) {
    create(path, content)
    return
  }
  const before = read(path)
  const keyOf = line => line.slice(0, line.indexOf('=')).trim()
  const valueOf = line => line.slice(line.indexOf('=') + 1).trim()
  const registryOf = line => valueOf(line).replace(/\/+$/, '')
  const existing = new Map(before.split(/\r?\n/)
    .filter(line => line.includes('=') && !/^\s*[#;]/.test(line))
    .map(line => [keyOf(line), line]))
  const lines = []
  for (const line of npmrc.npmrcLines) {
    const key = keyOf(line)
    if (!existing.has(key))
      lines.push(line)
    else if (key.startsWith('@') && registryOf(existing.get(key)) !== registryOf(line))
      plan.conflicts.push(`${shown(path)} maps ${key} to another registry; h34s packages come from https://npm.pkg.github.com`)
    else if (!key.startsWith('@') && valueOf(existing.get(key)) !== valueOf(line))
      plan.kept.push(`the existing ${key} line in ${shown(path)} (not shown)`)
  }
  if (lines.length > 0)
    edit(path, before, appended(before, '# h34s packages from GitHub Packages; the token comes from the environment', lines), `adds ${lines.length === 2 ? 'the @yuri4n scope and its token reference' : lines.map(keyOf).join(', ')}`)
}

// claude 2026-10-20: created: project/build.properties and project/plugins.sbt, created only when missing (ADR-0009)
function planProject(plan, sbt, sources, { create, shown }) {
  const properties = join(sbt, 'project/build.properties')
  if (!exists(properties)) {
    create(properties, buildProperties.render())
  }
  else {
    const declared = /^\s*sbt\.version\s*=\s*(\S+)/m.exec(read(properties))?.[1]
    if (declared && declared !== buildProperties.sbtVersion)
      plan.warnings.push(`${shown(properties)} sets sbt.version=${declared}; h34s is tested with sbt ${buildProperties.sbtVersion}.`)
  }
  const plugins = sources
    .filter(source => dirname(source.path) === join(sbt, 'project') && source.path.endsWith('.sbt'))
    .map(source => ({ source, version: /"sbt-scalajs"\s*%\s*"([^"]+)"/.exec(source.text)?.[1] }))
    .find(item => item.version)
  const pluginsPath = join(sbt, 'project/plugins.sbt')
  if (plugins) {
    if (plugins.version !== pluginsSbt.scalaJsVersion)
      plan.warnings.push(`${shown(plugins.source.path)} uses sbt-scalajs ${plugins.version}; the h34s libraries are built with Scala.js ${pluginsSbt.scalaJsVersion}. Use ${pluginsSbt.scalaJsVersion} or later.`)
  }
  else if (!exists(pluginsPath)) {
    create(pluginsPath, pluginsSbt.render())
  }
  else {
    plan.manual.push(`Add this line to ${shown(pluginsPath)}, which does not load Scala.js yet:\n      ${pluginsSbt.pluginLine}`)
  }
}

// claude 2026-10-20: changed: writes the plan; creates refuse an existing file, and edits refuse a file that changed since the plan; both stop with the file and what was written (ADR-0009, decision 1, review N5)
/** Writes the plan's actions in order. Gives the paths it wrote. */
export function applyPlan(plan) {
  const written = []
  const shown = path => relative(plan.cwd ?? process.cwd(), path) || path
  const stop = (action, what, cause) => new Error(
    `${shown(action.path)} ${what} while init ran, so init left it as it is and stopped. Written before it: ${written.length > 0 ? written.map(shown).join(', ') : 'nothing'}.`,
    cause ? { cause } : undefined,
  )
  for (const action of plan.actions) {
    mkdirSync(dirname(action.path), { recursive: true })
    if (action.kind === 'create') {
      try {
        writeFileSync(action.path, action.content, { flag: 'wx' })
      }
      catch (error) {
        throw error?.code === 'EEXIST' ? stop(action, 'appeared', error) : error
      }
    }
    else {
      if (read(action.path) !== action.before)
        throw stop(action, 'changed')
      writeFileSync(action.path, action.content)
    }
    written.push(action.path)
  }
  return written
}

// claude 2026-10-20: created: the names of the three paths in the summary (ADR-0009)
const pathNames = {
  1: 'a new project (path 1, layout A)',
  2: 'an existing Nuxt app (path 2, layout A)',
  3: 'an existing sbt build (path 3, layout B: the Nuxt app below the sbt build)',
}

// claude 2026-10-20: created: the summary and the next steps; it says whether the two variables are set, never their values (ADR-0009, decision 10)
/** The lines that init prints after a plan, for `env` (only the presence of the two variables is read). */
export function summary(plan, { env = process.env, dryRun = false } = {}) {
  const shown = path => relative(plan.cwd, path) || '.'
  const lines = [`[h34s] init: ${pathNames[plan.path]}, with the ${plan.api === 'zio' ? 'ZIO' : 'Kyo'} API.`]
  lines.push(`  Nuxt app: ${shown(plan.app)}`, `  sbt build: ${shown(plan.sbt)}`)
  const created = plan.actions.filter(action => action.kind === 'create')
  const edited = plan.actions.filter(action => action.kind === 'edit')
  if (created.length > 0)
    lines.push('', dryRun ? 'Would create:' : 'Created:', ...created.map(action => `  ${shown(action.path)}`))
  if (edited.length > 0)
    lines.push('', dryRun ? 'Would change:' : 'Changed:', ...edited.map(action => `  ${shown(action.path)}: ${action.what}`))
  if (plan.kept.length > 0)
    lines.push('', 'Kept:', ...plan.kept.map(item => `  ${item}`))
  if (plan.warnings.length > 0)
    lines.push('', 'Warnings:', ...plan.warnings.map(item => `  - ${item}`))
  if (plan.manual.length > 0)
    lines.push('', 'Do these yourself:', ...plan.manual.map(item => `  - ${item}`))
  const variables = ['GITHUB_PACKAGES_USER', 'GITHUB_PACKAGES_TOKEN']
    .map(name => `${name} is ${env[name] ? 'set' : 'not set'}`)
    .join(', ')
  const appPath = relative(plan.cwd, plan.app)
  lines.push(
    '',
    'Next steps:',
    `  1. Set GITHUB_PACKAGES_USER to your GitHub user name and GITHUB_PACKAGES_TOKEN to a classic token with read:packages, in your shell profile or secret manager. Now: ${variables}. pnpm and sbt read them; the project files only name them.`,
  )
  let step = 2
  if (appPath)
    lines.push(`  ${step++}. cd ${appPath}`)
  lines.push(
    `  ${step++}. pnpm install`,
    `  ${step++}. pnpm run scala:fast`,
    `  ${step++}. pnpm dev`,
    '  Then open http://localhost:3000. The page calls the Scala handler at /api/hello.',
  )
  return lines
}

// claude 2026-10-20: created: the lines for a plan that stops (ADR-0009, decision 1)
/** The lines that init prints when the plan has conflicts. */
export function conflictLines(plan) {
  return [
    '[h34s] init stopped and wrote nothing. Resolve these conflicts, then run init again:',
    ...plan.conflicts.map(item => `  - ${item}`),
  ]
}

// claude 2026-10-20: changed: the help of the init command names the stop in a directory with another project (ADR-0009, review S4)
export const initHelp = `Usage: h34s-scala init [directory] [--zio] [--dry-run]

Sets up h34s once in a project. Run it without installing anything first:
  pnpm dlx @yuri4n/h34s-nuxt@<version> init [directory]

It finds the adoption path from the files in the directory (default: the
current directory):
  a nuxt.config.* (or a package.json that lists nuxt)
                 an existing Nuxt app: adds the sbt build at the Nuxt root
  a build.sbt    an existing sbt build: creates the Nuxt app in web/ and an
                 h34s.sbt next to build.sbt, which init does not edit
  nothing yet    a new project: a minimal Nuxt app and its sbt build. The
                 directory may be missing, empty, or hold only repository
                 files such as .git, .gitignore, a README and a license.
When the parent directory has a build.sbt, a Nuxt app or a directory with
nothing yet becomes the Nuxt app of that sbt build. In a directory that holds
other files, init stops. When h34s.config.mjs exists, init changes nothing.

init checks every file before it writes any. It creates missing files only and
never overwrites one; it appends to .gitignore and .npmrc, merges into
package.json, and adds the module to nuxt.config when that edit is safe. On a
conflict it lists the files and writes nothing. It installs no packages, runs
no sbt and writes no token: the files name GITHUB_PACKAGES_USER and
GITHUB_PACKAGES_TOKEN.

--zio      Write the hello handler and the dependency for the ZIO API.
           The default is the Kyo API.
--dry-run  Print the plan and write nothing.`

// claude 2026-10-20: created: the init command of the CLI; gives the exit code (ADR-0009, decision 1)
/**
 * Runs `h34s-scala init` with the arguments after `init`. Prints through `out` and `err`, and
 * gives the exit code: 0 when it wrote the plan, printed a dry run, or found the project already
 * set up; 1 on a conflict or a usage error.
 */
export function runInit(args, { cwd = process.cwd(), env = process.env, out = console.log, err = console.error, version } = {}) {
  let parsed
  try {
    parsed = parseArgs({
      args,
      allowPositionals: true,
      options: {
        'zio': { type: 'boolean', default: false },
        'dry-run': { type: 'boolean', default: false },
        'help': { type: 'boolean', short: 'h', default: false },
      },
    })
  }
  catch (error) {
    err(`[h34s] ${error.message}\n${initHelp}`)
    return 1
  }
  if (parsed.values.help) {
    out(initHelp)
    return 0
  }
  if (parsed.positionals.length > 1) {
    err(`[h34s] init takes one directory, got ${parsed.positionals.length}\n${initHelp}`)
    return 1
  }
  const plan = planInit({
    directory: parsed.positionals[0] ?? '.',
    api: parsed.values.zio ? 'zio' : 'kyo',
    cwd,
    ...(version ? { version } : {}),
  })
  if (plan.alreadySetUp) {
    out(`[h34s] Already set up: ${relative(cwd, plan.alreadySetUp) || plan.alreadySetUp} exists. init runs once per project and changed nothing.`)
    return 0
  }
  if (plan.conflicts.length > 0) {
    err(conflictLines(plan).join('\n'))
    return 1
  }
  const dryRun = parsed.values['dry-run']
  if (!dryRun)
    applyPlan(plan)
  out(summary(plan, { env, dryRun }).join('\n'))
  if (dryRun)
    out('\n[h34s] Dry run: nothing was written.')
  return 0
}
