import { defineConfig } from '@yuri4n/h34s-nuxt/build'

// The Scala handlers declare their own routes (`.at(Route.get(...))`); `h34s-scala` collects them.
export default defineConfig({
  // claude 2026-10-20: created: the demo's sbt build is the repository build (ADR-0009)
  // `h34s-scala` runs sbt two directories up; the outputs stay in this application.
  sbtRoot: '../..',
  modules: [
    { project: 'nuxtsvr', output: 'server/.scala' },
    { project: 'kyoExample', output: 'server/.scala-kyo' },
  ],
})
