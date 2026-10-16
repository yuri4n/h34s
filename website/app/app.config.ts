export default defineAppConfig({
  seo: {
    title: 'h34s',
    description: 'A toolbox for Scala.js in your Nuxt app. '
      + 'Choose ZIO or Kyo handlers and provide your own services.',
  },
  header: { title: 'h34s' },
  ui: { colors: { primary: 'teal', neutral: 'stone' } },
  github: {
    url: 'https://github.com/yuri4n/h34s',
    branch: 'main',
    rootDir: 'website',
  },
  socials: { github: 'https://github.com/yuri4n/h34s' },
  toc: { title: 'On this page' },
})
