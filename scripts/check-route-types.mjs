import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { dirname, join, resolve } from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

// Type-checks the example's `$fetch` calls against the declarations that `h34s-scala` writes next
// to the linked handlers. Run it after linking (`pnpm run scala:fast`) and `nuxt prepare` in
// examples/nuxt, so that `.nuxt/types/nitro-routes.d.ts` lists the Scala routes.
const app = resolve(dirname(fileURLToPath(import.meta.url)), '../examples/nuxt')
const routes = join(app, '.nuxt/types/nitro-routes.d.ts')
if (!existsSync(routes))
  throw new Error(`Missing ${routes}: link the Scala handlers and run nuxt prepare in examples/nuxt first`)
const types = readFileSync(routes, 'utf8')
for (const route of ['/api/kyo/greeting', '/api/notes', '/api/test']) {
  if (!types.includes(`'${route}'`))
    throw new Error(`Nitro does not type ${route}: check the route manifests in examples/nuxt/server`)
}

// Only the typed calls: the rest of the demo app is not part of this check. Plain tsc does not
// read Vue components, which Nuxt's component types import; the shim declares them.
const config = join(app, '.nuxt/tsconfig.h34s-routes.json')
const shim = join(app, '.nuxt/h34s-vue-shim.d.ts')
writeFileSync(shim, 'declare module \'*.vue\' {\n  const component: unknown\n  export default component\n}\n')
writeFileSync(config, JSON.stringify({
  extends: './tsconfig.app.json',
  include: ['./nuxt.d.ts', './h34s-vue-shim.d.ts', '../app/utils/scala-api.ts', '../app/composables/useNotes.ts', '../test/route-types.ts'], // claude 2026-10-20: changed: also the composable that types useAsyncData with NoteView (ADR-0014)
}))
try {
  const tsc = createRequire(join(app, 'package.json')).resolve('typescript/bin/tsc')
  execFileSync(process.execPath, [tsc, '--noEmit', '-p', config], { cwd: app, stdio: 'inherit' })
  console.log('Passed: Nitro types the Scala routes from the generated declarations; $fetch results are precise')
}
finally {
  rmSync(config, { force: true })
  rmSync(shim, { force: true })
}
