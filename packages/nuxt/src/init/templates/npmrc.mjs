import { file } from './regions.mjs'

// claude 2026-10-20: created: the npm scope lines of the npm tooling guide; the token line names an environment variable (ADR-0009, decision 10)
/** The `.npmrc` lines: the `@yuri4n` scope and its token, as a reference to `GITHUB_PACKAGES_TOKEN`. */
export const npmrcLines = [
  '@yuri4n:registry=https://npm.pkg.github.com',
  '//npm.pkg.github.com/:_authToken=${GITHUB_PACKAGES_TOKEN}',
]

// claude 2026-10-20: created: a new .npmrc (ADR-0009)
/** Renders `.npmrc`. */
export function render() {
  return file(npmrcLines)
}
