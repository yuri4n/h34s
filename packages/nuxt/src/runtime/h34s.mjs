import { useRuntimeConfig, useStorage } from 'nitropack/runtime'
import { createH34sPlugin } from './context.mjs'

// Nitro transforms this file (the runtime directory is inlined), so `import.meta.dev`
// reflects the Nitro build mode rather than native Node ESM.
export default createH34sPlugin({
  useRuntimeConfig,
  useStorage,
  development: import.meta.dev === true,
})
