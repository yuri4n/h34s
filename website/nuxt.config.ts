const description = 'Write Scala handlers for Nuxt. Learn ZIO, Skunk, streaming, and Scala.js debugging with h34s.'

export default defineNuxtConfig({
  extends: ['@yuri4n/nuxt-docs'],
  site: { name: 'h34s' },
  compatibilityDate: '2026-10-09',
  devtools: { enabled: false },
  app: { head: { link: [{ rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' }] } },
  content: {
    build: {
      markdown: {
        highlight: { langs: ['scala', 'sql'] },
      },
    },
  },
  // System fonts keep the docs usable offline and avoid build-time font fetches.
  fonts: { providers: { google: false, bunny: false, fontshare: false, fontsource: false, adobe: false, googleicons: false, npm: false } },
  icon: { serverBundle: { collections: ['lucide', 'simple-icons'] } },
  llms: {
    title: 'h34s documentation',
    description,
    sections: [],
    notes: [
      'h34s is an unpublished Scala.js toolbox for Nuxt/Nitro. The current implementation uses ZIO; the first-class Kyo API is planned.',
      'Application modules own models, Skunk connections, and service provisioning. Production packaging and published artifacts are not verified.',
    ],
  },
  mcp: { name: 'h34s documentation' },
})
