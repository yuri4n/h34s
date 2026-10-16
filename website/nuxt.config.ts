import process from 'node:process'
import { fileURLToPath } from 'node:url'

const description = 'Use h34s to run Scala.js in your Nuxt app. '
  + 'Choose ZIO or Kyo handlers, optional adapters, and build tooling.'
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
      'h34s is a Scala.js toolbox for Nuxt/Nitro. Private Maven libraries '
      + 'and npm tooling are published at 0.1.0-alpha.1. Choose independent '
      + 'ZIO or Kyo APIs; their handlers execute on their own runtime.',
      'Define models and services, provide their dependencies, and mount '
      + 'handler exports in your application. AppHandler is an optional '
      + 'user-defined helper. Database setup and resource ownership are yours.',
      'examples/nuxt contains an optional demo with Skunk database access. '
      + 'website contains this separate documentation app. Automatic route '
      + 'generation, Nix, and deployable application packaging remain planned.',
    ],
  },
  mcp: { name: 'h34s documentation' },
})
