// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  modules: [
    '@yuri4n/h34s-nuxt',
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
  runtimeConfig: {
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
