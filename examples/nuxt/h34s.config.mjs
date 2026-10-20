import { defineConfig } from '@yuri4n/h34s-nuxt/build'

// The Scala handlers declare their own routes (`.at(Route.get(...))`); `h34s-scala` collects them.
export default defineConfig({
  modules: [
    { project: 'nuxtsvr', output: 'server/.scala' },
    { project: 'kyoExample', output: 'server/.scala-kyo' },
  ],
})
