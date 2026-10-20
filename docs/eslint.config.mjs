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
  ],
})
