import antfu from '@antfu/eslint-config'
import wrap from '@seahax/eslint-plugin-wrap'
import withNuxt from './.nuxt/eslint.config.mjs'

export default withNuxt(
  // Your custom configs here
  { ignores: ['**/.scala/*.{js, map}'] },
  antfu(
    {
      vue: true,
      typescript: true,
      formatters: true,
      stylistic: true,
    },
  ),

  [
    wrap.config({ maxLen: 80 }),
  ],
)
