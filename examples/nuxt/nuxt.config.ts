// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  // claude 2026-10-19: changed: scalaPrerender: 'stub' keeps the linked Scala.js out of Nitro's prerender bundle (issue #30).
  // The one prerendered route, Nuxt Content's /__nuxt_content/focuses/sql_dump.txt, calls no Scala.js route.
  // claude 2026-10-20: created: the region that the project tour shows (issue #52)
  // #region tour-h34s-module
  modules: [
    ['@yuri4n/h34s-nuxt', { scalaPrerender: 'stub' }],
    // #endregion tour-h34s-module
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

  // Private server settings for the ZIO Notes example, read with ZRuntimeConfig. Nitro overrides
  // each key at runtime: NUXT_DATABASE_HOST, NUXT_DATABASE_PORT, NUXT_DATABASE_USER,
  // NUXT_DATABASE_PASSWORD and NUXT_DATABASE_NAME. Supply the password only at runtime.
  // claude 2026-10-20: changed: both examples read the greeting; the region that the project tour shows (issue #52)
  // #region tour-greeting-config
  runtimeConfig: {
    // The greeting of both examples, read with RuntimeConfig.load (Kyo) and ZRuntimeConfig.load (ZIO).
    // Override: NUXT_GREETING_SALUTATION.
    greeting: {
      salutation: 'Hello',
    },
    // #endregion tour-greeting-config
    database: {
      host: '127.0.0.1',
      port: 5432,
      user: 'h34s',
      password: '',
      name: 'h34s',
    },
  },

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
