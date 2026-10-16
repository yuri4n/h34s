import { defineConfig } from '@yuri4n/h34s-nuxt/build'
import example from './h34s.config.mjs'

// The shared sbt build runs at the repository root. Rebase this example's
// outputs for the CLI while Nuxt uses the application-relative configuration.
export default defineConfig({
  modules: example.modules.map(module => ({
    ...module,
    output: `examples/nuxt/${module.output}`,
  })),
})
