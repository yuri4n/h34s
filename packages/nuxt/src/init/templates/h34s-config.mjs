import { file, region } from './regions.mjs'

// claude 2026-10-20: created: h34s.config.mjs, with sbtRoot only when the sbt build is above the app (ADR-0009)
/** Renders `h34s.config.mjs`. `sbtRoot` is the sbt build relative to the app, or `'.'`. */
export function render({ sbtRoot, project }) {
  return file(region('tour', [
    'import { defineConfig } from \'@yuri4n/h34s-nuxt/build\'',
    '',
    'export default defineConfig({',
    ...(sbtRoot === '.' ? [] : [`  sbtRoot: '${sbtRoot}',`]),
    `  modules: [{ project: '${project}', output: 'server/.scala' }],`,
    '})',
  ]))
}
