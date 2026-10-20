import yuri4n from '@yuri4n/eslint-config'

export default yuri4n({
  nodeTest: true,
  ignores: [
    '.nuxt/**',
    '.output/**',
    '.data/**',
    '.vercel/**',
    'vendor/**',
    'pnpm-lock.yaml',
    // claude 2026-10-20: created: GitHub-only maintainer files, outside the
    // site's content collection and never formatted for it (issue #25)
    'design/**',
    'maintainers/**',
    'development.md',
    'baseline.md',
  ],
})
