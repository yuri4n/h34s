import { createRequire } from 'node:module'

// Skunk 1.0.0-M12's SCRAM implementation calls require('crypto'), even in
// Scala.js ES-module output. Install Node's loader before accepting requests.
export default defineNitroPlugin(() => {
  const runtime = globalThis as typeof globalThis & {
    require?: ReturnType<typeof createRequire>
  }
  runtime.require ??= createRequire(import.meta.url)
})
