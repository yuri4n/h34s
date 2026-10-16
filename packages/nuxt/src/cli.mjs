#!/usr/bin/env node
import process from 'node:process'
import { linkScala, loadConfig } from './build.mjs'

const help = `Usage: h34s-scala <build|dev|watch> [--root <directory>] [--config <file>]

build  Clear generated outputs, fullLinkJS, and verify expected entries.
dev    fastLinkJS and verify expected entries.
watch  Keep sbt running and fastLinkJS on source changes.

Reads h34s.config.mjs from the app root. Requires Java and sbt on PATH.
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
    linkScala({ root: options.root, config, mode })
  }
}
catch (error) {
  console.error(`[h34s] ${error.message}`)
  process.exitCode = 1
}
