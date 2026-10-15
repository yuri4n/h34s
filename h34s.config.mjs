import { defineConfig } from '@yuri4n/h34s-nuxt/build'

export default defineConfig({
  modules: [
    {
      project: 'nuxtsvr',
      output: 'server/.scala',
      entries: ['test.js', 'notes-get.js', 'notes-post.js'],
    },
    {
      project: 'kyoExample',
      output: 'server/.scala-kyo',
      entries: ['kyo-greeting-get.js', 'kyo-greeting-post.js'],
    },
  ],
})
