import type { Config } from 'tailwindcss'
import typography from '@tailwindcss/typography'
import forms from '@tailwindcss/forms'
import aspectRatio from '@tailwindcss/aspect-ratio'

export default <Partial<Config>>{
  darkMode: ['class', '.dark-mode'],

  theme: {
    fontFamily: {
      'sans': ['Inter', 'system-ui', 'sans'],
      'serif': ['Alegreya', 'serif'],
      'sans-serif': ['Alegreya Sans', 'sans-serif'],
      'monospace': ['Inconsolata', 'monospace'],
    },
    extend: {},
  },

  plugins: [
    typography(),
    forms(),
    aspectRatio,

    // require('preline/plugin'),
  ],
}
