import { file } from './regions.mjs'

// claude 2026-10-20: created: the install scripts that a new Nuxt app needs under pnpm 10 (ADR-0009)
/** Renders `pnpm-workspace.yaml`: pnpm runs the install scripts of these dependencies only. */
export function render() {
  return file([
    '# pnpm runs the install scripts of these dependencies only.',
    'onlyBuiltDependencies:',
    '  - \'@parcel/watcher\'',
    '  - esbuild',
  ])
}
