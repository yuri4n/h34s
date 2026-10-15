import { resolve } from 'node:path'
import { scalaDevModules } from './build/scala-dev-modules.mjs'
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
      const scalaOutputs = ['server/.scala', 'server/.scala-kyo']
      config.devServer ??= {}
      config.devServer.watch ??= []
      config.devServer.watch.push(
        ...scalaOutputs.map(directory => resolve(config.rootDir!, directory)),
      )
      config.rollupConfig ??= {}
      config.rollupConfig.plugins ??= []
      // Load linked ESM directly in Node; relinking restarts the Nitro worker.
      // This avoids retaining large Scala.js graphs in Rollup's development cache.
      config.rollupConfig.plugins.push(
        scalaDevModules(config.rootDir!, scalaOutputs),
      )
      config.rollupConfig.plugins.push(
        scalaSourceMaps(
          config.rootDir!,
          scalaOutputs,
        ),
      )
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
