export default defineAppConfig({
  seo: {
    title: 'h34s',
    description: 'Scala handlers for Nuxt. '
      + 'A guide to ZIO, Skunk, and Scala.js.',
  },
  header: { title: 'h34s' },
  ui: { colors: { primary: 'teal', neutral: 'stone' } },
  github: {
    url: 'https://github.com/yuri4n/h34s',
    branch: 'feat/documentation-site',
    rootDir: 'website',
  },
  socials: { github: 'https://github.com/yuri4n/h34s' },
  toc: { title: 'On this page' },
})
