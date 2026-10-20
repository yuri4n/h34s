// The tests of `h34s-scala init` (ADR-0009). Every run starts from a clean temporary directory, with
// no sbt and no package install.
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { lstatSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, readlinkSync, realpathSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs' // claude 2026-10-20: changed: also the symlink calls (review P2)
import { tmpdir } from 'node:os'
import { dirname, join, relative, sep } from 'node:path'
import process from 'node:process'
// The package uses Node's runner without additional test dependencies.
// eslint-disable-next-line test/no-import-node-test
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { applyPlan, planInit } from '../src/init/init.mjs' // claude 2026-10-20: created: the plan's order and the write step (review S2, N5)
import { addModule, hasModule } from '../src/init/nuxt-config-edit.mjs' // claude 2026-10-20: changed: also hasModule (review P2)

// claude 2026-10-20: created: the CLI, the package version and a fake token (ADR-0009)
const cli = fileURLToPath(new URL('../src/cli.mjs', import.meta.url))
const version = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version
/** A value that must never reach a written file or the output. */
const fakeToken = 'ghp_h34sInitTestTokenValue0123456789'

// claude 2026-10-20: created: a clean temporary directory (ADR-0009)
function temporary(t) {
  // The real path: the CLI prints paths relative to its working directory (macOS /var).
  const directory = realpathSync(mkdtempSync(join(tmpdir(), 'h34s-init-')))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  return directory
}

// claude 2026-10-20: created: runs init with fake registry variables (ADR-0009)
/** Runs `h34s-scala init <args>` in `cwd` with the two registry variables set to fake values. */
function init(cwd, args = [], env = {}) {
  const result = spawnSync(process.execPath, [cli, 'init', ...args], {
    cwd,
    encoding: 'utf8',
    env: { ...process.env, GITHUB_PACKAGES_USER: 'octo', GITHUB_PACKAGES_TOKEN: fakeToken, ...env },
  })
  return { status: result.status, stdout: result.stdout, stderr: result.stderr, output: result.stdout + result.stderr }
}

// claude 2026-10-20: created: the files of a directory (ADR-0009)
/** Every file below `directory`: its path with `/` separators, mapped to its text. */
function tree(directory) {
  const files = {}
  for (const entry of readdirSync(directory, { recursive: true, withFileTypes: true })) {
    const path = join(entry.parentPath, entry.name)
    if (statSync(path).isFile())
      files[relative(directory, path).split(sep).join('/')] = readFileSync(path, 'utf8')
  }
  return Object.fromEntries(Object.entries(files).sort(([a], [b]) => a.localeCompare(b)))
}

// claude 2026-10-20: created: writes files (ADR-0009)
function write(directory, files) {
  for (const [path, text] of Object.entries(files)) {
    mkdirSync(dirname(join(directory, path)), { recursive: true })
    writeFileSync(join(directory, path), text)
  }
}

// claude 2026-10-20: created: a Nuxt starter (ADR-0009)
/** A Nuxt 4 starter's package.json and nuxt.config.ts. */
const nuxtStarter = {
  'package.json': `${JSON.stringify({
    name: 'shop',
    type: 'module',
    private: true,
    scripts: { 'build': 'nuxt build', 'dev': 'nuxt dev', 'generate': 'nuxt generate', 'preview': 'nuxt preview', 'postinstall': 'nuxt prepare' },
    dependencies: { 'nuxt': '^4.3.1', 'vue': '^3.5.28', 'vue-router': '^4.6.4' },
  }, null, 2)}\n`,
  'nuxt.config.ts': `// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  compatibilityDate: '2025-07-15',
  devtools: { enabled: true },
  modules: [
    '@nuxt/eslint',
  ],
})
`,
  '.gitignore': '.nuxt\nnode_modules\n',
}

// claude 2026-10-20: created: an sbt build with Scala.js (ADR-0009)
/** An existing sbt build with Scala.js. */
const sbtBuild = {
  'build.sbt': 'lazy val core = project.in(file("core"))\n',
  'project/build.properties': 'sbt.version=1.11.7\n',
  'project/plugins.sbt': 'addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")\n',
}

// claude 2026-10-20: created: the files of path 1 (ADR-0009)
const path1Files = [
  '.gitignore',
  '.jvmopts',
  '.npmrc',
  'app/app.vue',
  'build.sbt',
  'h34s.config.mjs',
  'nuxt.config.ts',
  'package.json',
  'pnpm-workspace.yaml',
  'project/build.properties',
  'project/plugins.sbt',
  'scala-server/src/main/scala/myapp/Hello.scala',
  'tsconfig.json',
]

// claude 2026-10-20: created: the files of the Nuxt app of path 3 (ADR-0009)
/** The files of the Nuxt app that path 3 creates in web/. */
const webFiles = [
  'web/.gitignore',
  'web/.npmrc',
  'web/app/app.vue',
  'web/h34s.config.mjs',
  'web/nuxt.config.ts',
  'web/package.json',
  'web/pnpm-workspace.yaml',
  'web/tsconfig.json',
]

// claude 2026-10-20: changed: the test "never overwrites: a file that init would create is a ..."; a file of path 1 makes the directory another project's, so init stops before its plan (ADR-0009, review S4)
test('never overwrites: a file that init would create is a conflict, init exits 1 and writes nothing', (t) => {
  const cases = [
    // Path 1: a new project. Any file that init would create makes the directory hold a project.
    ...['package.json', 'pnpm-workspace.yaml', 'tsconfig.json', 'app/app.vue', 'scala-server/src/main/scala/myapp/Hello.scala']
      .map(file => ({ name: `path 1, ${file}`, before: { [`my-app/${file}`]: 'kept\n' }, args: ['my-app'], message: 'my-app is not empty and has no nuxt.config.*, no package.json that lists nuxt and no build.sbt' })),
    // Path 2: an existing Nuxt app; its Hello.scala and, beside a build.sbt, the h34s.sbt.
    { name: 'path 2, Hello.scala', before: { ...nuxtStarter, 'scala-server/src/main/scala/app/Hello.scala': 'kept\n' }, args: [], conflict: 'scala-server/src/main/scala/app/Hello.scala', cwd: 'app' },
    { name: 'path 2, h34s.sbt', before: { ...nuxtStarter, 'build.sbt': '\n', 'h34s.sbt': 'kept\n' }, args: [], conflict: 'h34s.sbt', cwd: 'app' },
    // Path 3: an existing sbt build.
    ...['h34s.sbt', 'web/package.json', 'web/pnpm-workspace.yaml', 'web/tsconfig.json', 'web/app/app.vue', 'scala-server/src/main/scala/lib/Hello.scala']
      .map(file => ({ name: `path 3, ${file}`, before: { ...sbtBuild, [file]: 'kept\n' }, args: [], conflict: file, cwd: 'lib' })),
  ]
  for (const item of cases) {
    const root = temporary(t)
    const cwd = item.cwd ? join(root, item.cwd) : root
    write(cwd, item.before)
    const before = tree(root)
    const result = init(cwd, item.args)
    assert.equal(result.status, 1, `${item.name}: exit code\n${result.output}`)
    assert.match(result.stderr, /init stopped and wrote nothing/, item.name)
    const message = item.message ?? `${item.conflict} exists; init creates it and never overwrites a file`
    assert.ok(result.stderr.includes(message), `${item.name}: names the conflict\n${result.stderr}`)
    assert.deepEqual(tree(root), before, `${item.name}: nothing was written`)
  }
})

// claude 2026-10-20: created: the test "a file that appears between the plan and the write is not ..." (review N5)
test('a file that appears between the plan and the write is not overwritten, and the error names it and what was written', (t) => {
  const root = temporary(t)
  const plan = planInit({ directory: 'my-app', cwd: root, version })
  assert.deepEqual(plan.conflicts, [])
  const creates = plan.actions.filter(action => action.kind === 'create')
  const late = creates[2]
  mkdirSync(dirname(late.path), { recursive: true })
  writeFileSync(late.path, 'kept\n')
  assert.throws(() => applyPlan(plan), (error) => {
    const shown = path => relative(root, path)
    assert.equal(error.message, `${shown(late.path)} appeared while init ran, so init left it as it is and stopped. Written before it: ${creates.slice(0, 2).map(action => shown(action.path)).join(', ')}.`)
    assert.equal(error.cause?.code, 'EEXIST')
    return true
  })
  assert.equal(readFileSync(late.path, 'utf8'), 'kept\n')
})

// claude 2026-10-20: created: the test "path 1: a directory that holds another project stops init; ..." (review S4)
test('path 1: a directory that holds another project stops init; a fresh clone with only repository files does not', (t) => {
  for (const files of [
    // A home directory: init must not add a project to it or append to its .npmrc.
    { '.npmrc': 'save-exact=true\n', '.bashrc': 'export PATH\n' },
    // A Maven project and a Node package without Nuxt.
    { 'pom.xml': '<project/>\n' },
    { 'package.json': '{ "name": "tool" }\n' },
  ]) {
    const root = temporary(t)
    write(root, files)
    const result = init(root)
    assert.equal(result.status, 1, result.output)
    assert.match(result.stderr, /init stopped and wrote nothing/)
    assert.ok(result.stderr.includes('. is not empty and has no nuxt.config.*, no package.json that lists nuxt and no build.sbt'), result.stderr)
    assert.match(result.stderr, /give it a new directory for a new project: h34s-scala init my-app/)
    assert.deepEqual(tree(root), files)
  }
  const root = join(temporary(t), 'my-app')
  const clone = { '.git/HEAD': 'ref: refs/heads/main\n', 'README.md': '# my-app\n', 'LICENSE': 'MIT\n', '.gitignore': 'notes.txt\n' }
  write(root, clone)
  const result = init(root)
  assert.equal(result.status, 0, result.output)
  assert.match(result.stdout, /a new project \(path 1, layout A\)/)
  const files = tree(root)
  for (const file of ['.git/HEAD', 'README.md', 'LICENSE'])
    assert.equal(files[file], clone[file], `${file} is unchanged`)
  assert.ok(files['.gitignore'].startsWith('notes.txt\n\n# Added by h34s-scala init\n.output\n'), files['.gitignore'])
  for (const file of path1Files.filter(file => file !== '.gitignore'))
    assert.ok(file in files, `${file} is created`)
})

// claude 2026-10-20: created: the test "path 1: init in an empty directory writes a minimal ..." (ADR-0009)
test('path 1: init in an empty directory writes a minimal Nuxt app and the layout-A sbt build', (t) => {
  const root = temporary(t)
  const app = join(root, 'my-app')
  mkdirSync(app)
  const result = init(app)
  assert.equal(result.status, 0, result.output)
  const files = tree(app)
  assert.deepEqual(Object.keys(files), path1Files)
  assert.match(result.stdout, /a new project \(path 1, layout A\), with the Kyo API/)
  for (const file of path1Files)
    assert.ok(result.stdout.includes(`  ${file}\n`), `the summary lists ${file}`)
  assert.equal(files['h34s.config.mjs'], `import { defineConfig } from '@yuri4n/h34s-nuxt/build'

export default defineConfig({
  modules: [{ project: 'server', output: 'server/.scala' }],
})
`)
  assert.match(files['build.sbt'], /^\/\/ build\.sbt\nimport org\.scalajs\.linker\.interface\.\{ModuleKind, OutputPatterns\}\n\nlazy val server = project\.in\(file\("scala-server"\)\)/)
  assert.ok(files['build.sbt'].includes(`libraryDependencies += "dev.yuri4n" %%% "kyo-h3-ext" % "${version}",`))
  assert.ok(files['build.sbt'].includes('(LocalRootProject / baseDirectory).value / "server" / ".scala",'))
  assert.ok(files['build.sbt'].includes('ThisBuild / resolvers += "h34s GitHub Packages" at'))
  assert.ok(files['build.sbt'].includes('// server / excludeDependencies += ExclusionRule("io.github.cquiroz", "locales-full-currencies-db_sjs1_3")'), 'the locale exclusion is a commented line')
  const manifest = JSON.parse(files['package.json'])
  assert.equal(manifest.name, 'my-app')
  assert.equal(manifest.dependencies.nuxt, '4.3.1')
  assert.equal(manifest.dependencies.h3, '1.15.4')
  assert.equal(manifest.devDependencies['@yuri4n/h34s-nuxt'], version)
  assert.equal(manifest.scripts['scala:fast'], 'h34s-scala dev')
  assert.match(files['nuxt.config.ts'], /modules: \['@yuri4n\/h34s-nuxt'\],/)
  assert.match(files['nuxt.config.ts'], /preset: 'node-server',/)
  assert.match(files['app/app.vue'], /\$fetch\('\/api\/hello'\)/)
  assert.deepEqual(JSON.parse(files['tsconfig.json']).references.map(item => item.path), ['./.nuxt/tsconfig.app.json', './.nuxt/tsconfig.server.json', './.nuxt/tsconfig.shared.json', './.nuxt/tsconfig.node.json'])
  assert.match(files['.gitignore'], /^server\/\.scala\/$/m)
  assert.match(files['.gitignore'], /^target\/$/m)
  assert.equal(files['.jvmopts'], '-Xss4m\n-Xmx4g\n')
  assert.equal(files['project/build.properties'], '# project/build.properties\nsbt.version=1.11.7\n')
  assert.equal(files['project/plugins.sbt'], '// project/plugins.sbt\naddSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")\n')
  const hello = files['scala-server/src/main/scala/myapp/Hello.scala']
  assert.match(hello, /^package myapp\n\nimport dev\.yuri4n\.exts\.kyoh3\.\*\n/)
  assert.ok(hello.includes('val hello: EventHandler = KHandler.define(_ => Greeting("Hello from Scala")).at(Route.get("/api/hello"))'))
  for (const [file, text] of Object.entries(files))
    assert.doesNotMatch(text, /#(?:end)?region|claude \d{4}-\d{2}-\d{2}:/, `${file} holds a marker`)
  assert.match(result.stdout, /Next steps:\n {2}1\. Set GITHUB_PACKAGES_USER .* Now: GITHUB_PACKAGES_USER is set, GITHUB_PACKAGES_TOKEN is set\./)
  assert.match(result.stdout, / {2}2\. pnpm install\n {2}3\. pnpm run scala:fast\n {2}4\. pnpm dev\n/)
})

// claude 2026-10-20: created: the test "path 1: init <directory> creates the directory, and the summary ..." (ADR-0009)
test('path 1: init <directory> creates the directory, and the summary says to cd into it', (t) => {
  const root = temporary(t)
  const result = init(root, ['my-app'])
  assert.equal(result.status, 0, result.output)
  assert.deepEqual(Object.keys(tree(root)), path1Files.map(file => `my-app/${file}`))
  assert.match(result.stdout, / {2}2\. cd my-app\n {2}3\. pnpm install\n/)
})

// claude 2026-10-20: changed: the test "path 2: init adds layout A to a Nuxt starter, ..."; the write order moved to a plan test (ADR-0009, review S2)
test('path 2: init adds layout A to a Nuxt starter, merges package.json, edits nuxt.config.ts and appends to .gitignore', (t) => {
  const root = join(temporary(t), 'shop')
  write(root, nuxtStarter)
  const result = init(root)
  assert.equal(result.status, 0, result.output)
  const files = tree(root)
  assert.deepEqual(Object.keys(files), [
    '.gitignore',
    '.jvmopts',
    '.npmrc',
    'build.sbt',
    'h34s.config.mjs',
    'nuxt.config.ts',
    'package.json',
    'project/build.properties',
    'project/plugins.sbt',
    'scala-server/src/main/scala/shop/Hello.scala',
  ])
  assert.match(result.stdout, /an existing Nuxt app \(path 2, layout A\)/)
  assert.equal(files['nuxt.config.ts'], nuxtStarter['nuxt.config.ts'].replace('  modules: [\n', '  modules: [\n    \'@yuri4n/h34s-nuxt\',\n'))
  const manifest = JSON.parse(files['package.json'])
  assert.deepEqual(manifest.scripts, {
    'build': 'nuxt build',
    'dev': 'nuxt dev',
    'generate': 'nuxt generate',
    'preview': 'nuxt preview',
    'postinstall': 'nuxt prepare',
    'scala:fast': 'h34s-scala dev',
    'scala:watch': 'h34s-scala watch',
    'scala:full': 'h34s-scala build',
    'dev:scala': 'pnpm run scala:fast && pnpm run dev',
  })
  assert.equal(manifest.dependencies.h3, '1.15.4')
  assert.equal(manifest.devDependencies['@yuri4n/h34s-nuxt'], version)
  assert.ok(files['package.json'].startsWith('{\n  "name": "shop",\n'), 'keeps the key order and the indentation')
  assert.ok(files['.gitignore'].startsWith(nuxtStarter['.gitignore']), 'keeps the existing lines')
  assert.equal(files['.gitignore'].slice(nuxtStarter['.gitignore'].length), '\n# Added by h34s-scala init\n.metals/\n.bsp/\ntarget/\nproject/target/\nproject/project/\nproject/metals.sbt\nserver/.scala/\n')
  assert.match(result.stdout, /Changed:\n {2}package\.json: adds the scripts scala:fast, scala:watch, scala:full, dev:scala; adds @yuri4n\/h34s-nuxt .* to devDependencies; adds h3 1\.15\.4 to dependencies\n/)
  assert.match(result.stdout, / {2}nuxt\.config\.ts: adds '@yuri4n\/h34s-nuxt' to modules\n/)
  // dev and build are the app's own scripts: init keeps them and prints the change.
  assert.match(result.stdout, /In package\.json, set the script "dev" to "node --enable-source-maps node_modules\/nuxt\/bin\/nuxt\.mjs dev".* init keeps your "nuxt dev"\./)
  assert.match(result.stdout, /In package\.json, set the script "build" to "pnpm run scala:full && nuxt build": a production build must link the Scala\.js first.* init keeps your "nuxt build"\./)
})

// claude 2026-10-20: created: the test "the plan creates h34s.config.mjs first and edits nuxt.config last" (review S2)
test('the plan creates h34s.config.mjs first and edits nuxt.config last, because the module stops Nuxt without the config', (t) => {
  const root = join(temporary(t), 'shop')
  write(root, nuxtStarter)
  const plan = planInit({ cwd: root, version })
  assert.deepEqual(plan.conflicts, [])
  const steps = plan.actions.map(action => `${action.kind} ${relative(root, action.path)}`)
  assert.equal(steps[0], 'create h34s.config.mjs', steps.join('\n'))
  assert.equal(steps.at(-1), 'edit nuxt.config.ts', steps.join('\n'))
  const firstEdit = plan.actions.findIndex(action => action.kind === 'edit')
  assert.ok(firstEdit > 0 && plan.actions.slice(firstEdit).every(action => action.kind === 'edit'), `every create comes before every edit:\n${steps.join('\n')}`)
})

// claude 2026-10-20: created: the test "path 2 beside a build.sbt: init writes h34s.sbt and leaves ..." (ADR-0009)
test('path 2 beside a build.sbt: init writes h34s.sbt and leaves build.sbt and the project files as they are', (t) => {
  const root = temporary(t)
  write(root, { ...nuxtStarter, ...sbtBuild, 'project/plugins.sbt': 'addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.20.1")\n' })
  const before = tree(root)
  const result = init(root, ['--zio'])
  assert.equal(result.status, 0, result.output)
  const files = tree(root)
  for (const file of ['build.sbt', 'project/build.properties', 'project/plugins.sbt'])
    assert.equal(files[file], before[file], `${file} is unchanged`)
  assert.ok(files['h34s.sbt'].startsWith('// h34s.sbt\n'))
  assert.ok(files['h34s.sbt'].includes(`"dev.yuri4n" %%% "zio-h3-ext" % "${version}"`))
  assert.ok(files['h34s.sbt'].includes('(LocalRootProject / baseDirectory).value / "server" / ".scala",'))
  assert.equal(files['h34s.config.mjs'].includes('sbtRoot'), false, 'the sbt build is the app root')
  assert.match(result.stdout, /Warnings:\n {2}- project\/plugins\.sbt uses sbt-scalajs 1\.20\.1; the h34s libraries are built with Scala\.js 1\.22\.0\./)
})

// claude 2026-10-20: created: the test "path 3: init in an sbt build creates the Nuxt ..." (ADR-0009)
test('path 3: init in an sbt build creates the Nuxt app in web/ and an h34s.sbt next to the untouched build.sbt', (t) => {
  const root = temporary(t)
  const lib = join(root, 'lib')
  write(lib, sbtBuild)
  const result = init(lib)
  assert.equal(result.status, 0, result.output)
  const files = tree(lib)
  assert.deepEqual(Object.keys(files), [
    '.jvmopts',
    'build.sbt',
    'h34s.sbt',
    'project/build.properties',
    'project/plugins.sbt',
    'scala-server/src/main/scala/lib/Hello.scala',
    ...webFiles,
  ].sort((a, b) => a.localeCompare(b)))
  for (const file of Object.keys(sbtBuild))
    assert.equal(files[file], sbtBuild[file], `${file} is unchanged`)
  assert.match(result.stdout, /an existing sbt build \(path 3, layout B/)
  assert.equal(files['web/h34s.config.mjs'], `import { defineConfig } from '@yuri4n/h34s-nuxt/build'

export default defineConfig({
  sbtRoot: '..',
  modules: [{ project: 'server', output: 'server/.scala' }],
})
`)
  assert.ok(files['h34s.sbt'].includes('(LocalRootProject / baseDirectory).value / "web" / "server" / ".scala",'))
  assert.ok(files['h34s.sbt'].includes('ThisBuild / resolvers += "h34s GitHub Packages" at'))
  assert.equal(JSON.parse(files['web/package.json']).name, 'lib-web')
  assert.doesNotMatch(files['web/.gitignore'], /target\//, 'the sbt entries belong to the sbt build')
  assert.match(files['web/.gitignore'], /^server\/\.scala\/$/m)
  assert.match(result.stdout, / {2}2\. cd web\n/)
})

// claude 2026-10-20: created: the test "path 3: init web in the sbt build gives the ..." (ADR-0009)
test('path 3: init web in the sbt build gives the same tree as init in it', (t) => {
  const first = join(temporary(t), 'lib')
  const second = join(temporary(t), 'lib')
  write(first, sbtBuild)
  write(second, sbtBuild)
  assert.equal(init(first).status, 0)
  const result = init(second, ['web'])
  assert.equal(result.status, 0, result.output)
  assert.match(result.stdout, /an existing sbt build \(path 3, layout B/)
  assert.deepEqual(tree(second), tree(first))
})

// claude 2026-10-20: created: the test "path 3 with a Nuxt app in web/: init, init web and init in web ..." (review S1, N4)
test('path 3 with a Nuxt app in web/: init, init web and init in web give the same layout B, and add h34s to the app', (t) => {
  const runs = [[[], '.'], [['web'], '.'], [[], 'web']].map(([args, from]) => {
    const lib = join(temporary(t), 'lib')
    write(lib, sbtBuild)
    write(join(lib, 'web'), nuxtStarter)
    const result = init(join(lib, from), args)
    assert.equal(result.status, 0, `${args.join(' ')} from ${from}\n${result.output}`)
    assert.match(result.stdout, /an existing sbt build \(path 3, layout B/)
    return tree(lib)
  })
  assert.deepEqual(runs[1], runs[0], 'init web gives the same tree as init')
  assert.deepEqual(runs[2], runs[0], 'init in web/ gives the same tree as init')
  const files = runs[0]
  for (const file of Object.keys(sbtBuild))
    assert.equal(files[file], sbtBuild[file], `${file} is unchanged`)
  assert.equal(files['web/build.sbt'], undefined, 'no sbt build is nested in the Nuxt app')
  assert.equal(Object.keys(files).some(file => file.startsWith('web/project/') || file.startsWith('web/scala-server/')), false)
  assert.equal(files['web/h34s.config.mjs'], `import { defineConfig } from '@yuri4n/h34s-nuxt/build'

export default defineConfig({
  sbtRoot: '..',
  modules: [{ project: 'server', output: 'server/.scala' }],
})
`)
  assert.ok(files['h34s.sbt'].includes('(LocalRootProject / baseDirectory).value / "web" / "server" / ".scala",'))
  assert.ok('scala-server/src/main/scala/lib/Hello.scala' in files)
  const manifest = JSON.parse(files['web/package.json'])
  assert.equal(manifest.name, 'shop', 'the app keeps its package.json')
  assert.equal(manifest.scripts['scala:fast'], 'h34s-scala dev')
  assert.equal(manifest.devDependencies['@yuri4n/h34s-nuxt'], version)
  assert.equal(files['web/nuxt.config.ts'], nuxtStarter['nuxt.config.ts'].replace('  modules: [\n', '  modules: [\n    \'@yuri4n/h34s-nuxt\',\n'))
  assert.equal(files['web/.gitignore'], `${nuxtStarter['.gitignore']}\n# Added by h34s-scala init\nserver/.scala/\n`)
})

// claude 2026-10-20: created: the test "path 3: a build that keeps its own resolver, .jvmopts ..." (ADR-0009)
test('path 3: a build that keeps its own resolver, .jvmopts and plugins file gets no duplicate, and a plugins file without Scala.js gets a printed line', (t) => {
  const root = temporary(t)
  write(root, {
    'build.sbt': 'ThisBuild / resolvers += "mine" at "https://maven.pkg.github.com/yuri4n/h34s"\nlazy val core = project\n',
    'project/plugins.sbt': 'addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.5")\n',
    '.jvmopts': '-Xmx2g\n',
  })
  const before = tree(root)
  const result = init(root)
  assert.equal(result.status, 0, result.output)
  const files = tree(root)
  for (const file of Object.keys(before))
    assert.equal(files[file], before[file], `${file} is unchanged`)
  assert.equal(files['h34s.sbt'].includes('ThisBuild / resolvers'), false, 'no second resolver')
  assert.equal(files['project/build.properties'], '# project/build.properties\nsbt.version=1.11.7\n', 'a missing file is created')
  assert.match(result.stdout, /Do these yourself:\n {2}- Add this line to project\/plugins\.sbt, which does not load Scala\.js yet:\n {6}addSbtPlugin\("org\.scala-js" % "sbt-scalajs" % "1\.22\.0"\)\n/)
  assert.match(result.stdout, /Kept:\n {2}the h34s resolver that your sbt build already has\n {2}\.jvmopts as it is/)
})

// claude 2026-10-20: created: the test "path 3: the resolver and the credentials are found ..." (review P1)
test('path 3: the resolver and the credentials are found independently, and h34s.sbt adds the one the build lacks', (t) => {
  const resolver = 'ThisBuild / resolvers += "mine" at "https://maven.pkg.github.com/yuri4n/h34s"\n'
  const credentials = 'ThisBuild / credentials += Credentials("GitHub Package Registry", "maven.pkg.github.com", sys.env("GITHUB_PACKAGES_USER"), sys.env("GITHUB_PACKAGES_TOKEN"))\n'
  const written = {
    resolver: 'ThisBuild / resolvers += "h34s GitHub Packages" at\n  "https://maven.pkg.github.com/yuri4n/h34s"\n',
    credentials: 'ThisBuild / credentials ++= (for {\n  user <- sys.env.get("GITHUB_PACKAGES_USER")\n  token <- sys.env.get("GITHUB_PACKAGES_TOKEN")\n} yield Credentials(\n  "GitHub Package Registry",\n  "maven.pkg.github.com",\n  user,\n  token,\n)).toSeq\n',
  }
  const keptResolver = 'the h34s resolver that your sbt build already has'
  const keptCredentials = 'the GitHub Packages credentials that your sbt build already has'
  for (const item of [
    { name: 'the resolver only', files: { 'build.sbt': `${resolver}lazy val core = project\n` }, adds: ['credentials'], kept: [keptResolver] },
    { name: 'the credentials only', files: { 'build.sbt': `${credentials}lazy val core = project\n` }, adds: ['resolver'], kept: [keptCredentials] },
    { name: 'both', files: { 'build.sbt': `${resolver}${credentials}lazy val core = project\n` }, adds: [], kept: [keptResolver, keptCredentials] },
    { name: 'neither', files: { 'build.sbt': 'lazy val core = project\n' }, adds: ['resolver', 'credentials'], kept: [] },
    // The template's own credentials stanza, in another sbt file of the build.
    { name: 'both, in two files', files: { 'build.sbt': `${resolver}lazy val core = project\n`, 'credentials.sbt': written.credentials }, adds: [], kept: [keptResolver, keptCredentials] },
  ]) {
    const root = temporary(t)
    write(root, { ...sbtBuild, ...item.files })
    const result = init(root)
    assert.equal(result.status, 0, `${item.name}\n${result.output}`)
    const sbt = tree(root)['h34s.sbt']
    for (const stanza of ['resolver', 'credentials'])
      assert.equal(sbt.includes(written[stanza]), item.adds.includes(stanza), `${item.name}: h34s.sbt ${item.adds.includes(stanza) ? 'adds' : 'does not add'} the ${stanza}\n${sbt}`)
    assert.equal((sbt.match(/ThisBuild \//g) ?? []).length, item.adds.length, `${item.name}: no other ThisBuild setting`)
    for (const line of [keptResolver, keptCredentials])
      assert.equal(result.stdout.includes(`  ${line}\n`), item.kept.includes(line), `${item.name}: ${line}\n${result.stdout}`)
  }
})

// claude 2026-10-20: created: the test "path 3: a commented-out Scala.js plugin does not load Scala.js ..." (review P2)
test('path 3: a commented-out Scala.js plugin does not load Scala.js, and an active one does', (t) => {
  const plugin = 'addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")'
  const printed = /Do these yourself:\n {2}- Add this line to project\/plugins\.sbt, which does not load Scala\.js yet:\n {6}addSbtPlugin\("org\.scala-js" % "sbt-scalajs" % "1\.22\.0"\)\n/
  for (const item of [
    { name: 'a line comment', files: { 'project/plugins.sbt': `// ${plugin}\n` }, loaded: false },
    { name: 'a block comment', files: { 'project/plugins.sbt': `/*\n${plugin}\n*/\n` }, loaded: false },
    // Scala block comments nest, so the plugin line is still inside the outer comment.
    { name: 'a nested block comment', files: { 'project/plugins.sbt': `/* outer /* inner */ ${plugin} */\n` }, loaded: false },
    // No project/plugins.sbt: init creates it.
    { name: 'a comment in another plugins file', files: { 'project/build.properties': 'sbt.version=1.11.7\n', 'project/scalajs.sbt': `// ${plugin}\n` }, loaded: false, creates: true },
    { name: 'an active line with a comment after it', files: { 'project/plugins.sbt': `${plugin} // Scala.js\n` }, loaded: true },
    { name: 'an active line after a string with comment markers', files: { 'project/plugins.sbt': `val note = "/* // not a comment"\n${plugin}\n` }, loaded: true },
  ]) {
    const root = temporary(t)
    write(root, { 'build.sbt': 'lazy val core = project\n', 'project/build.properties': 'sbt.version=1.11.7\n', ...item.files })
    const before = tree(root)
    const result = init(root)
    assert.equal(result.status, 0, `${item.name}\n${result.output}`)
    const files = tree(root)
    for (const file of Object.keys(before))
      assert.equal(files[file], before[file], `${item.name}: ${file} is unchanged`)
    assert.equal(printed.test(result.stdout), !item.loaded && !item.creates, `${item.name}: the printed line\n${result.stdout}`)
    assert.equal(files['project/plugins.sbt'], item.creates ? `// project/plugins.sbt\n${plugin}\n` : before['project/plugins.sbt'], `${item.name}: project/plugins.sbt`)
    assert.equal(result.stdout.includes('sbt-scalajs'), !item.loaded && !item.creates, `${item.name}: no warning about sbt-scalajs\n${result.stdout}`)
  }
})

// claude 2026-10-20: created: the test "path 3: a commented-out resolver or credentials stanza ..." (review P1, P2)
test('path 3: a commented-out resolver or credentials stanza is not in the build, and h34s.sbt adds it', (t) => {
  for (const [name, build] of [
    ['line comments', '// ThisBuild / resolvers += "mine" at "https://maven.pkg.github.com/yuri4n/h34s"\n// credentials += Credentials("GitHub Package Registry", "maven.pkg.github.com", "u", sys.env("GITHUB_PACKAGES_TOKEN"))\nlazy val core = project\n'],
    ['a block comment', '/*\nThisBuild / resolvers += "mine" at "https://maven.pkg.github.com/yuri4n/h34s"\ncredentials += Credentials("GitHub Package Registry", "maven.pkg.github.com", "u", sys.env("GITHUB_PACKAGES_TOKEN"))\n*/\nlazy val core = project\n'],
  ]) {
    const root = temporary(t)
    write(root, { ...sbtBuild, 'build.sbt': build })
    const result = init(root)
    assert.equal(result.status, 0, `${name}\n${result.output}`)
    const sbt = tree(root)['h34s.sbt']
    assert.ok(sbt.includes('ThisBuild / resolvers += "h34s GitHub Packages" at'), `${name}: the resolver\n${sbt}`)
    assert.ok(sbt.includes('ThisBuild / credentials ++= (for {'), `${name}: the credentials\n${sbt}`)
    assert.equal(result.stdout.includes('Kept:'), false, `${name}\n${result.stdout}`)
  }
})

// claude 2026-10-20: created: the test "path 3: a build that already defines the server project ..." (ADR-0009)
test('path 3: a build that already defines the server project or uses scala-server is a conflict', (t) => {
  const root = temporary(t)
  write(root, { ...sbtBuild, 'build.sbt': 'lazy val server = project.in(file("scala-server"))\n' })
  const before = tree(root)
  const result = init(root)
  assert.equal(result.status, 1, result.output)
  assert.ok(result.stderr.includes('build.sbt already defines an sbt value named server'))
  assert.ok(result.stderr.includes('build.sbt already uses the directory scala-server'))
  assert.deepEqual(tree(root), before)
})

// claude 2026-10-20: changed: the test "already set up: h34s.config.mjs stops init with exit 0 and ..."; also init web in a set-up sbt build (ADR-0009, review S5)
test('already set up: h34s.config.mjs stops init with exit 0 and changes nothing', (t) => {
  for (const [files, args, config] of [
    [{ 'h34s.config.mjs': 'export default {}\n' }, [], 'h34s.config.mjs'],
    [{ ...nuxtStarter, 'h34s.config.mjs': 'export default {}\n' }, [], 'h34s.config.mjs'],
    [{ ...sbtBuild, 'web/h34s.config.mjs': 'export default {}\n' }, [], 'web/h34s.config.mjs'],
    // An sbt build that holds its own config, as layout A: no Nuxt app is added in web/.
    [{ ...sbtBuild, 'h34s.config.mjs': 'export default {}\n' }, [], 'h34s.config.mjs'],
    [{ ...sbtBuild, 'web/h34s.config.mjs': 'export default {}\n' }, ['web'], 'web/h34s.config.mjs'],
    // init web in an sbt build that is already set up as layout A.
    [{ ...sbtBuild, 'h34s.config.mjs': 'export default {}\n' }, ['web'], 'h34s.config.mjs'],
  ]) {
    const root = temporary(t)
    write(root, files)
    const result = init(root, args)
    assert.equal(result.status, 0, result.output)
    assert.equal(result.stdout, `[h34s] Already set up: ${config} exists. init runs once per project and changed nothing.\n`)
    assert.deepEqual(tree(root), files)
  }
})

// claude 2026-10-20: created: the test "a script of the same name with another value is ..." (ADR-0009)
test('a script of the same name with another value is a conflict, and nothing is written', (t) => {
  const root = temporary(t)
  const manifest = JSON.parse(nuxtStarter['package.json'])
  manifest.scripts['scala:fast'] = 'sbt fastLinkJS'
  write(root, { ...nuxtStarter, 'package.json': `${JSON.stringify(manifest, null, 2)}\n` })
  const before = tree(root)
  const result = init(root)
  assert.equal(result.status, 1, result.output)
  assert.ok(result.stderr.includes('package.json has the script "scala:fast" set to "sbt fastLinkJS"; init would add "h34s-scala dev"'))
  assert.deepEqual(tree(root), before)
})

// claude 2026-10-20: created: the test "the same script with the same value, a listed package ..." (ADR-0009)
test('the same script with the same value, a listed package and h3 are kept as they are', (t) => {
  const root = temporary(t)
  const manifest = JSON.parse(nuxtStarter['package.json'])
  manifest.scripts['scala:fast'] = 'h34s-scala dev'
  manifest.scripts.dev = 'node --enable-source-maps node_modules/nuxt/bin/nuxt.mjs dev'
  manifest.dependencies.h3 = '1.15.4'
  manifest.devDependencies = { '@yuri4n/h34s-nuxt': '0.1.0-alpha.1' }
  write(root, { ...nuxtStarter, 'package.json': `${JSON.stringify(manifest, null, '\t')}\n` })
  const result = init(root)
  assert.equal(result.status, 0, result.output)
  const after = JSON.parse(tree(root)['package.json'])
  assert.equal(after.devDependencies['@yuri4n/h34s-nuxt'], '0.1.0-alpha.1', 'a listed version is not changed')
  assert.match(tree(root)['package.json'], /^\{\n\t"name"/, 'keeps tab indentation')
  assert.match(result.stdout, /package\.json lists @yuri4n\/h34s-nuxt 0\.1\.0-alpha\.1, and the sbt build uses the Scala libraries/)
  assert.doesNotMatch(result.stdout, /set the script "dev"/)
})

// claude 2026-10-20: created: the names below a directory, symlinks included and not followed (review P2)
/** Every entry below `directory`, with `/` separators, sorted; a symlink is listed, never followed. */
function listing(directory) {
  return readdirSync(directory, { recursive: true }).map(name => name.split(sep).join('/')).sort()
}

// claude 2026-10-20: created: the test "a dangling symlink where init would create a file is a conflict ..." (review P2)
test('a dangling symlink where init would create a file is a conflict, and init writes nothing', (t) => {
  for (const name of ['build.sbt', '.npmrc', 'h34s.config.mjs']) {
    const root = temporary(t)
    write(root, nuxtStarter)
    symlinkSync('missing-target', join(root, name))
    const before = listing(root)
    const result = init(root)
    assert.equal(result.status, 1, `${name}\n${result.output}`)
    assert.match(result.stderr, /^\[h34s\] init stopped and wrote nothing\. Resolve these conflicts, then run init again:\n/, name)
    assert.ok(result.stderr.includes(`  - ${name} exists; init creates it and never overwrites a file\n`), `${name}\n${result.stderr}`)
    assert.deepEqual(listing(root), before, `${name}: nothing was created`)
    assert.equal(readlinkSync(join(root, name)), 'missing-target', `${name}: the symlink stays`)
  }
})

// claude 2026-10-20: created: the test "a dangling symlink at a file that init creates only when missing ..." (review P2)
test('a dangling symlink at a file that init creates only when missing is kept as it is', (t) => {
  for (const name of ['project/build.properties', '.jvmopts']) {
    const root = temporary(t)
    write(root, { 'build.sbt': 'lazy val core = project\n', 'project/plugins.sbt': sbtBuild['project/plugins.sbt'] })
    symlinkSync('missing-target', join(root, name))
    const result = init(root)
    assert.equal(result.status, 0, `${name}\n${result.output}`)
    assert.ok(lstatSync(join(root, name)).isSymbolicLink(), name)
    assert.equal(readlinkSync(join(root, name)), 'missing-target', `${name}: the symlink stays`)
    assert.ok(listing(root).includes('h34s.sbt'), `${name}: init wrote the plan`)
  }
})

// claude 2026-10-20: created: the test "a non-empty server/.scala or an existing /api/hello route file is ..." (ADR-0009)
test('a non-empty server/.scala or an existing /api/hello route file is a conflict', (t) => {
  for (const file of ['server/.scala/old.js', 'server/api/hello.get.ts', 'server/api/hello.ts']) {
    const root = temporary(t)
    write(root, { ...nuxtStarter, [file]: 'kept\n' })
    const before = tree(root)
    const result = init(root)
    assert.equal(result.status, 1, `${file}\n${result.output}`)
    assert.match(result.stderr, file.startsWith('server/.scala') ? /server\/\.scala exists and is not empty/ : /serves GET \/api\/hello/)
    assert.deepEqual(tree(root), before)
  }
})

// claude 2026-10-20: created: the test "the nuxt.config edit adds the module in the safe forms" (ADR-0009)
test('the nuxt.config edit adds the module in the safe forms', () => {
  const name = '@yuri4n/h34s-nuxt'
  const forms = [
    ['export default defineNuxtConfig({\n  modules: [\n    \'@nuxt/eslint\',\n  ],\n})\n', 'export default defineNuxtConfig({\n  modules: [\n    \'@yuri4n/h34s-nuxt\',\n    \'@nuxt/eslint\',\n  ],\n})\n'],
    ['export default defineNuxtConfig({\n  modules: [\'@nuxt/eslint\'],\n})\n', 'export default defineNuxtConfig({\n  modules: [\'@yuri4n/h34s-nuxt\', \'@nuxt/eslint\'],\n})\n'],
    ['export default defineNuxtConfig({\n  modules: [],\n})\n', 'export default defineNuxtConfig({\n  modules: [\'@yuri4n/h34s-nuxt\'],\n})\n'],
    ['export default defineNuxtConfig({\n  modules: [\n  ],\n})\n', 'export default defineNuxtConfig({\n  modules: [\n    \'@yuri4n/h34s-nuxt\',\n  ],\n})\n'],
    ['export default defineNuxtConfig({\n  devtools: { enabled: true },\n})\n', 'export default defineNuxtConfig({\n  modules: [\'@yuri4n/h34s-nuxt\'],\n  devtools: { enabled: true },\n})\n'],
    ['export default defineNuxtConfig({})\n', 'export default defineNuxtConfig({\n  modules: [\'@yuri4n/h34s-nuxt\'],\n})\n'],
    ['export default defineNuxtConfig({ ssr: false })\n', 'export default defineNuxtConfig({ modules: [\'@yuri4n/h34s-nuxt\'], ssr: false })\n'],
    ['export default defineNuxtConfig({\n  modules: ["@nuxt/eslint"],\n  app: { head: { title: "Shop" } },\n})\n', 'export default defineNuxtConfig({\n  modules: ["@yuri4n/h34s-nuxt", "@nuxt/eslint"],\n  app: { head: { title: "Shop" } },\n})\n'],
    // Nitro's own modules key is not Nuxt's: the top-level modules is added.
    ['export default defineNuxtConfig({\n  nitro: { modules: [] },\n})\n', 'export default defineNuxtConfig({\n  modules: [\'@yuri4n/h34s-nuxt\'],\n  nitro: { modules: [] },\n})\n'],
    // A comment and a string with brackets do not change the structure.
    ['// a [bracket] {\nexport default defineNuxtConfig({\n  /* modules: [x] */\n  css: [\'~/a[b].css\'],\n  modules: [\'@nuxt/ui\', [\'@nuxtjs/i18n\', { locales: [\'en\'] }]],\n})\n', '// a [bracket] {\nexport default defineNuxtConfig({\n  /* modules: [x] */\n  css: [\'~/a[b].css\'],\n  modules: [\'@yuri4n/h34s-nuxt\', \'@nuxt/ui\', [\'@nuxtjs/i18n\', { locales: [\'en\'] }]],\n})\n'],
    ['export default defineNuxtConfig({\r\n  modules: [\r\n    \'@nuxt/eslint\',\r\n  ],\r\n})\r\n', 'export default defineNuxtConfig({\r\n  modules: [\r\n    \'@yuri4n/h34s-nuxt\',\r\n    \'@nuxt/eslint\',\r\n  ],\r\n})\r\n'],
  ]
  for (const [before, after] of forms)
    assert.equal(addModule(before, name), after, before)
})

// claude 2026-10-20: created: the test "the nuxt.config edit gives up on the shapes it does ..." (ADR-0009)
test('the nuxt.config edit gives up on the shapes it does not read safely', () => {
  for (const text of [
    'export default {\n  modules: [],\n}\n',
    'const a = defineNuxtConfig({})\nexport default defineNuxtConfig({})\n',
    'export default defineNuxtConfig({\n  ...base,\n})\n',
    'export default defineNuxtConfig({\n  modules,\n})\n',
    'export default defineNuxtConfig({\n  modules: list,\n})\n',
    'export default defineNuxtConfig({\n  modules: [],\n  \'modules\': [],\n})\n',
    'export default defineNuxtConfig({\n  app: { baseURL: `/${base}` },\n})\n',
    'export default defineNuxtConfig({\n  vite: { include: [/\\.vue$/] },\n})\n',
    'export default defineNuxtConfig(config)\n',
    'export default defineNuxtConfig({\n  modules: [\n',
    'export default defineNuxtConfig({\n  title: \'open\n})\n',
  ]) {
    assert.equal(addModule(text, '@yuri4n/h34s-nuxt'), null, text)
  }
})

// claude 2026-10-20: created: the test "path 2: only an entry of the top-level modules array ..." (review P2)
test('path 2: only an entry of the top-level modules array lists the module; a comment or another string does not', (t) => {
  const config = body => `export default defineNuxtConfig({\n${body}})\n`
  for (const [name, before, after] of [
    ['a commented-out entry', config('  modules: [\n    // \'@yuri4n/h34s-nuxt\',\n    \'@nuxt/eslint\',\n  ],\n'), config('  modules: [\n    \'@yuri4n/h34s-nuxt\',\n    // \'@yuri4n/h34s-nuxt\',\n    \'@nuxt/eslint\',\n  ],\n')],
    ['a block comment', config('  /* modules: [\'@yuri4n/h34s-nuxt\'] */\n  ssr: true,\n'), config('  modules: [\'@yuri4n/h34s-nuxt\'],\n  /* modules: [\'@yuri4n/h34s-nuxt\'] */\n  ssr: true,\n')],
    ['a string of another key', config('  app: { head: { title: \'@yuri4n/h34s-nuxt\' } },\n  modules: [\'@nuxt/eslint\'],\n'), config('  app: { head: { title: \'@yuri4n/h34s-nuxt\' } },\n  modules: [\'@yuri4n/h34s-nuxt\', \'@nuxt/eslint\'],\n')],
    ['Nitro\'s modules', config('  nitro: { modules: [\'@yuri4n/h34s-nuxt\'] },\n'), config('  modules: [\'@yuri4n/h34s-nuxt\'],\n  nitro: { modules: [\'@yuri4n/h34s-nuxt\'] },\n')],
    ['an entry', config('  modules: [\'@nuxt/eslint\', \'@yuri4n/h34s-nuxt\'],\n'), null],
    ['an entry with options', config('  modules: [[\'@yuri4n/h34s-nuxt\', {}], \'@nuxt/eslint\'],\n'), null],
  ]) {
    const root = temporary(t)
    write(root, { ...nuxtStarter, 'nuxt.config.ts': before })
    const result = init(root)
    assert.equal(result.status, 0, `${name}\n${result.output}`)
    assert.equal(tree(root)['nuxt.config.ts'], after ?? before, name)
    assert.equal(result.stdout.includes('  nuxt.config.ts, which already lists @yuri4n/h34s-nuxt\n'), after === null, `${name}: kept\n${result.stdout}`)
    assert.equal(result.stdout.includes('  nuxt.config.ts: adds \'@yuri4n/h34s-nuxt\' to modules\n'), after !== null, `${name}: changed\n${result.stdout}`)
  }
})

// claude 2026-10-20: created: the test "hasModule reads only the entries of the top-level modules array" (review P2)
test('hasModule reads only the entries of the top-level modules array', () => {
  const config = body => `export default defineNuxtConfig({\n${body}})\n`
  for (const [text, listed] of [
    [config('  modules: [\'@yuri4n/h34s-nuxt\'],\n'), true],
    [config('  modules: ["@nuxt/ui", "@yuri4n/h34s-nuxt"],\n'), true],
    [config('  modules: [[\'@yuri4n/h34s-nuxt\', { debug: true }]],\n'), true],
    // A spread hides other keys, but the modules array literal lists the module.
    [config('  ...base,\n  modules: [\'@yuri4n/h34s-nuxt\'],\n'), true],
    [config('  modules: [\n    // \'@yuri4n/h34s-nuxt\',\n  ],\n'), false],
    [config('  modules: [\'@nuxt/ui\', [\'@nuxtjs/i18n\', { label: \'@yuri4n/h34s-nuxt\' }]],\n'), false],
    [config('  modules: [\'@yuri4n/h34s-nuxt-extra\'],\n'), false],
    [config('  nitro: { modules: [\'@yuri4n/h34s-nuxt\'] },\n'), false],
    [`// modules: ['@yuri4n/h34s-nuxt']\n${config('')}`, false],
    // Shapes in which init cannot tell: it prints the line to add.
    [config('  ...base,\n'), null],
    [config('  modules: list,\n'), null],
    [config('  vite: { include: [/\\.vue$/] },\n  modules: [\'@yuri4n/h34s-nuxt\'],\n'), null],
  ]) {
    assert.equal(hasModule(text, '@yuri4n/h34s-nuxt'), listed, text)
  }
})

// claude 2026-10-20: created: the test "path 2: a nuxt.config that the edit does not read ..." (ADR-0009)
test('path 2: a nuxt.config that the edit does not read gets the printed line and stays unchanged', (t) => {
  const root = temporary(t)
  const config = 'import base from \'./base\'\nexport default defineNuxtConfig({\n  ...base,\n})\n'
  write(root, { ...nuxtStarter, 'nuxt.config.ts': config })
  const result = init(root)
  assert.equal(result.status, 0, result.output)
  assert.equal(tree(root)['nuxt.config.ts'], config)
  assert.match(result.stdout, /Do these yourself:\n(?: {2}- .*\n)* {2}- Add '@yuri4n\/h34s-nuxt' to the modules in nuxt\.config\.ts\. .*\n {6}modules: \['@yuri4n\/h34s-nuxt'\],\n/)
})

// claude 2026-10-20: created: the test "--zio writes the ZIO handler and depends on zio-h3-ext" (ADR-0009)
test('--zio writes the ZIO handler and depends on zio-h3-ext', (t) => {
  const root = temporary(t)
  const result = init(root, ['my-app', '--zio'])
  assert.equal(result.status, 0, result.output)
  const files = tree(join(root, 'my-app'))
  assert.match(result.stdout, /with the ZIO API/)
  assert.ok(files['build.sbt'].includes(`"dev.yuri4n" %%% "zio-h3-ext" % "${version}"`))
  assert.equal(files['build.sbt'].includes('kyo-h3-ext'), false)
  const hello = files['scala-server/src/main/scala/myapp/Hello.scala']
  assert.match(hello, /^import dev\.yuri4n\.exts\.zioh3\.\*$/m)
  assert.ok(hello.includes('val hello: EventHandler = ZHandler.define(_ => ZIO.succeed(Greeting("Hello from Scala"))).at(Route.get("/api/hello"))'))
})

// claude 2026-10-20: created: the test "the pinned versions are the package's own version" (ADR-0009)
test('the pinned versions are the package\'s own version', (t) => {
  const root = temporary(t)
  assert.equal(init(root, ['kyo']).status, 0)
  assert.equal(init(root, ['zio', '--zio']).status, 0)
  for (const [app, artifact] of [['kyo', 'kyo-h3-ext'], ['zio', 'zio-h3-ext']]) {
    const files = tree(join(root, app))
    assert.equal(JSON.parse(files['package.json']).devDependencies['@yuri4n/h34s-nuxt'], version)
    assert.deepEqual([...files['build.sbt'].matchAll(/"dev\.yuri4n" %%% "([\w-]+)" % "([^"]+)"/g)].map(match => [match[1], match[2]]), [[artifact, version]])
  }
})

// claude 2026-10-20: created: the test "no token value reaches a written file or the output; ..." (ADR-0009)
test('no token value reaches a written file or the output; the files name the environment variables', (t) => {
  const root = temporary(t)
  write(join(root, 'nuxt'), nuxtStarter)
  write(join(root, 'nuxt'), { '.npmrc': `//npm.pkg.github.com/:_authToken=${fakeToken}\n` })
  write(join(root, 'sbt'), sbtBuild)
  const runs = [init(root, ['new']), init(join(root, 'nuxt')), init(join(root, 'sbt'))]
  for (const result of runs) {
    assert.equal(result.status, 0, result.output)
    assert.equal(result.output.includes(fakeToken), false, 'the output holds the token')
  }
  const files = tree(root)
  const holders = Object.entries(files).filter(([, text]) => text.includes(fakeToken)).map(([file]) => file)
  assert.deepEqual(holders, ['nuxt/.npmrc'], 'only the file that held it before')
  assert.match(runs[1].stdout, /the existing \/\/npm\.pkg\.github\.com\/:_authToken line in \.npmrc \(not shown\)/)
  for (const npmrc of ['new/.npmrc', 'sbt/web/.npmrc'])
    assert.equal(files[npmrc], '@yuri4n:registry=https://npm.pkg.github.com\n//npm.pkg.github.com/:_authToken=${GITHUB_PACKAGES_TOKEN}\n')
  assert.equal(files['nuxt/.npmrc'], `//npm.pkg.github.com/:_authToken=${fakeToken}\n\n# h34s packages from GitHub Packages; the token comes from the environment\n@yuri4n:registry=https://npm.pkg.github.com\n`)
  for (const sbt of ['new/build.sbt', 'nuxt/build.sbt', 'sbt/h34s.sbt']) {
    assert.ok(files[sbt].includes('user <- sys.env.get("GITHUB_PACKAGES_USER")'), sbt)
    assert.ok(files[sbt].includes('token <- sys.env.get("GITHUB_PACKAGES_TOKEN")'), sbt)
  }
})

// claude 2026-10-20: created: the test ".npmrc: a different registry for the @yuri4n scope is a ..." (ADR-0009)
test('.npmrc: a different registry for the @yuri4n scope is a conflict', (t) => {
  const root = temporary(t)
  write(root, { ...nuxtStarter, '.npmrc': '@yuri4n:registry=https://registry.npmjs.org\n' })
  const before = tree(root)
  const result = init(root)
  assert.equal(result.status, 1, result.output)
  assert.match(result.stderr, /\.npmrc maps @yuri4n:registry to another registry/)
  assert.deepEqual(tree(root), before)
})

// claude 2026-10-20: created: the test ".npmrc: the registry with a trailing slash is the same registry" (review N2)
test('.npmrc: the registry with a trailing slash is the same registry', (t) => {
  const root = temporary(t)
  write(root, { ...nuxtStarter, '.npmrc': '@yuri4n:registry=https://npm.pkg.github.com/\n' })
  const result = init(root)
  assert.equal(result.status, 0, result.output)
  assert.equal(tree(root)['.npmrc'], '@yuri4n:registry=https://npm.pkg.github.com/\n\n# h34s packages from GitHub Packages; the token comes from the environment\n//npm.pkg.github.com/:_authToken=${GITHUB_PACKAGES_TOKEN}\n')
})

// claude 2026-10-20: created: the test "the package.json merge and the appends keep CRLF line endings" (review N3)
test('the package.json merge and the appends keep CRLF line endings', (t) => {
  const root = temporary(t)
  const crlf = text => text.replaceAll('\n', '\r\n')
  write(root, { ...nuxtStarter, 'package.json': crlf(nuxtStarter['package.json']), '.gitignore': crlf(nuxtStarter['.gitignore']) })
  const result = init(root)
  assert.equal(result.status, 0, result.output)
  const files = tree(root)
  for (const file of ['package.json', '.gitignore']) {
    assert.match(files[file], /\r\n$/, file)
    assert.doesNotMatch(files[file], /(?<!\r)\n/, `${file} has only CRLF line endings`)
  }
  assert.equal(JSON.parse(files['package.json']).scripts['scala:fast'], 'h34s-scala dev')
})

// claude 2026-10-20: created: the test "--dry-run prints the plan and writes nothing" (ADR-0009)
test('--dry-run prints the plan and writes nothing', (t) => {
  const root = temporary(t)
  write(root, nuxtStarter)
  const before = tree(root)
  const result = init(root, ['--dry-run'])
  assert.equal(result.status, 0, result.output)
  assert.match(result.stdout, /Would create:\n {2}h34s\.config\.mjs\n/)
  assert.match(result.stdout, /Would change:\n {2}package\.json: /)
  assert.match(result.stdout, /\[h34s\] Dry run: nothing was written\.\n$/)
  assert.deepEqual(tree(root), before)
})

// claude 2026-10-20: created: the test "init rejects an unknown option and a second directory" (ADR-0009)
test('init rejects an unknown option and a second directory', (t) => {
  const root = temporary(t)
  for (const args of [['--yes'], ['a', 'b']]) {
    const result = init(root, args)
    assert.equal(result.status, 1, args.join(' '))
    assert.match(result.stderr, /Usage: h34s-scala init \[directory\] \[--zio\] \[--dry-run\]/)
  }
  assert.deepEqual(tree(root), {})
  const help = init(root, ['--help'])
  assert.equal(help.status, 0)
  assert.match(help.stdout, /^Usage: h34s-scala init/)
  const top = spawnSync(process.execPath, [cli, '--help'], { encoding: 'utf8' })
  assert.match(top.stdout, /^ {7}h34s-scala init \[directory\] \[--zio\] \[--dry-run\]$/m, 'the CLI help names init')
})
