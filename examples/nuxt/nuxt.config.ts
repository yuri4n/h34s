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
