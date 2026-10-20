#!/usr/bin/env node
import process from 'node:process'
import { linkScala, loadConfig, watchScala, writeRoutes } from './build.mjs'

// claude 2026-10-20: changed: the help names sbtRoot (ADR-0009)
const help = `Usage: h34s-scala <build|dev|watch> [--root <directory>] [--config <file>]

build  Clear generated outputs, fullLinkJS, verify expected entries, and write
       the route manifests and TypeScript declarations.
dev    fastLinkJS, verify expected entries, and write the route manifests and
       TypeScript declarations.
watch  Keep sbt running, fastLinkJS on source changes, and write the route
       manifests and declarations again after each link.

Reads h34s.config.mjs from the app root (--root, default: the current
directory) and runs sbt in the config's sbtRoot (default: the config file's
directory). The outputs stay under the app root. Requires Java and sbt on PATH.
Run Nuxt with --enable-source-maps for original Scala stack traces.`

try {
  const args = process.argv.slice(2)
  if (args.length === 0 || args[0] === '--help' || args[0] === '-h') {
    console.log(help)
  }
  else {
    const mode = args.shift()
    if (!['build', 'dev', 'watch'].includes(mode))
      throw new Error(`Unknown command: ${mode}\n${help}`)
    const options = {}
    while (args.length) {
      const flag = args.shift()
      const key = { '--root': 'root', '--config': 'configFile' }[flag]
      if (
        !key
        || !args[0]
        || args[0].startsWith('--')
        || options[key] !== undefined
      ) {
        throw new Error(`Invalid or repeated option: ${flag}\n${help}`)
      }
      options[key] = args.shift()
    }
    const config = await loadConfig(options)
    if (mode === 'watch') {
      await watchScala({ root: options.root, config })
    }
    else {
      linkScala({ root: options.root, config, mode })
      const outputs = await writeRoutes({ root: options.root, config })
      const routes = outputs.reduce((count, output) => count + output.routes.length, 0)
      // claude 2026-10-20: changed: also counts the named models (ADR-0014)
      const models = outputs.reduce((count, output) => count + output.models.names.length, 0)
      console.log(`[h34s] ${routes} route${routes === 1 ? '' : 's'} and ${models} model${models === 1 ? '' : 's'} in ${outputs.map(output => output.output).join(', ')}`)
    }
  }
}
catch (error) {
  console.error(`[h34s] ${error.message}`)
  process.exitCode = 1
}
