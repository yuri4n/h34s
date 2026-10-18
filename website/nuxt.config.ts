import process from 'node:process'
import { fileURLToPath } from 'node:url'

const description = 'Use h34s to write Nuxt server routes in Scala.js: '
  + 'a Kyo core, a first-class ZIO adapter, optional bridges, and build tooling.'
const docsLayerApp = fileURLToPath(
  new URL('./vendor/yuri4n.dev/layers/docs/app/', import.meta.url),
)

export default defineNuxtConfig({
  extends: ['@yuri4n/nuxt-docs'],
  site: { name: 'h34s' },
  compatibilityDate: '2026-10-09',
  devtools: { enabled: false },
  // Nuxt omits nested layers outside layers/ from the app project includes.
  // Explicit Vue globs also keep Oxc from falling through to the parent app.
  typescript: {
    appTsConfig: {
      include: [`${docsLayerApp}**/*`, `${docsLayerApp}**/*.vue`],
    },
  },
  // Nitro 2's runtime autodetection stops at Node 22.
  nitro: { vercel: { functions: { runtime: 'nodejs24.x' } } },
  // Vercel supplies image optimization; do not ship IPX/Sharp there.
  image: {
    provider: process.env.NITRO_PRESET === 'vercel' ? 'vercel' : undefined,
  },
  app: {
    head: {
      link: [{ rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' }],
    },
  },
  content: {
    build: {
      markdown: {
        highlight: {
          langs: ['scala', 'sql'],
          theme: {
            default: 'github-light',
            light: 'github-light',
            dark: 'github-dark',
          },
        },
      },
    },
  },
  // System fonts avoid build-time font fetches.
  fonts: {
    providers: {
      google: false,
      bunny: false,
      fontshare: false,
      fontsource: false,
      adobe: false,
      googleicons: false,
      npm: false,
    },
  },
  icon: {
    provider: 'server',
    fallbackToApi: false,
    serverBundle: { collections: ['lucide', 'simple-icons'] },
    // These icons come from content navigation, outside the Vue scanner.
    clientBundle: { icons: ['lucide:book-open', 'lucide:library'] },
  },
  llms: {
    title: 'h34s documentation',
    description,
    sections: [],
    notes: [
      'h34s is a Scala.js toolbox for Nuxt/Nitro. Its core is written in Kyo; '
      + 'ZIO is a first-class adapter over that core. Kyo applications never '
      + 'depend on ZIO; ZIO applications get Kyo transitively.',
      'This documentation describes h34s 0.2.0-alpha.1, whose Maven and npm '
      + 'packages are private on GitHub Packages. The published 0.1.0-alpha.1 '
      + 'libraries and npm tooling have an older API with independent '
      + 'runtimes.',
      'A handler is a function from an immutable Request to the response body. '
      + 'Values are sent as {"data": ...}, errors as {"errors": [{"message"}]}, '
      + 'Unit as 204. Only SafeError data reaches clients.',
      'Define models and services, wire them in an optional user-defined '
      + 'AppHandler, and mount handler exports with one-line route files. '
      + 'Schemas, databases (kyo-sql, Skunk) and storage are optional choices.',
      'examples/nuxt contains an optional demo; website contains this separate '
      + 'documentation app. Route generation, Nix, and deployable application '
      + 'packaging remain planned.',
    ],
  },
  mcp: { name: 'h34s documentation' },
})
