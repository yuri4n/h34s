export default defineAppConfig({
  seo: {
    title: 'h34s',
    description: 'Write Nuxt server routes in Scala.js. '
      + 'A Kyo core, a first-class ZIO adapter, and your own services.',
  },
  header: { title: 'h34s' },
  ui: { colors: { primary: 'teal', neutral: 'stone' } },
  github: {
    url: 'https://github.com/yuri4n/h34s',
    branch: 'main',
    // claude 2026-10-20: changed: the site and its content live in docs/
    rootDir: 'docs',
  },
  socials: { github: 'https://github.com/yuri4n/h34s' },
  toc: { title: 'On this page' },
})
