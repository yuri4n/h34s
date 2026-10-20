import { file } from './regions.mjs'

// claude 2026-10-20: created: one page that calls the hello route with $fetch (ADR-0009, decision 8)
/** Renders `app/app.vue`: one page that shows the answer of `/api/hello`. */
export function render() {
  return file([
    '<script setup lang="ts">',
    '// The Scala handler in scala-server/ answers /api/hello. Link it first: pnpm run scala:fast.',
    'const { data, error } = await useAsyncData(\'hello\', () => $fetch(\'/api/hello\'))',
    '</script>',
    '',
    '<template>',
    '  <main>',
    '    <h1>Nuxt with Scala.js handlers</h1>',
    '    <p v-if="error" role="alert">',
    '      The request to /api/hello failed: {{ error.message }}',
    '    </p>',
    '    <pre v-else>{{ data }}</pre>',
    '  </main>',
    '</template>',
  ])
}
