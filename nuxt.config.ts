import { scalaSourceMaps } from './build/scala-source-maps.mjs'

// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  modules: [
    '@nuxt/content',
    '@nuxt/eslint',
    '@nuxt/fonts',
    '@nuxt/icon',
    '@nuxt/image',
    '@nuxt/test-utils',
    '@nuxtjs/i18n',
    '@nuxtjs/tailwindcss',
    '@vueuse/nuxt',
    'nuxt-typed-router',
    'shadcn-nuxt',
  ],
  devtools: { enabled: true },

  css: ['@/assets/css/main.css', '@/assets/css/tailwind.css'],

  content: {
    build: {
      markdown: {
        toc: {
          depth: 3,
          searchDepth: 3,
        },
      },
    },
  },

  compatibilityDate: '2025-05-15',

  hooks: {
    'nitro:config': (config) => {
      if (!config.dev)
        return
      config.sourceMap = true
      config.rollupConfig ??= {}
      config.rollupConfig.plugins ??= []
      config.rollupConfig.plugins.push(scalaSourceMaps(config.rootDir!, ['server/.scala', 'server/.scala-kyo']))
      config.rollupConfig.output = {
        ...config.rollupConfig.output,
        sourcemapExcludeSources: false,
      }
    },
  },

  eslint: {
    config: {
      standalone: false,
      stylistic: true,
    },
  },

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  shadcn: {
    prefix: 'Shad',
    componentDir: '@/components/ui/shadcn',
  },
})
