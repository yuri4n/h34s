// The package.json of a new Nuxt app, and the scripts that init adds to an existing one.

// claude 2026-10-20: created: the scripts of the npm tooling guide (ADR-0009, decision 5)
/** The h34s scripts, as the npm tooling guide lists them. */
export const scripts = {
  'scala:fast': 'h34s-scala dev',
  'scala:watch': 'h34s-scala watch',
  'scala:full': 'h34s-scala build',
  'dev': 'node --enable-source-maps node_modules/nuxt/bin/nuxt.mjs dev',
  'dev:scala': 'pnpm run scala:fast && pnpm run dev',
  'build': 'pnpm run scala:full && nuxt build',
}

// claude 2026-10-20: created: the Nuxt version that a new app pins (ADR-0009, decision 8)
/** The Nuxt version of the example and of the packed-module check. */
export const nuxtVersion = '4.3.1'

// claude 2026-10-20: created: the h3 version of the guides (ADR-0009)
/** The h3 version that the Scala libraries are built against. */
export const h3Version = '1.15.4'

// claude 2026-10-20: created: the scripts block that the npm tooling guide shows (ADR-0009)
/** The scripts as the guide's `package.json` block shows them. */
export function scriptsBlock() {
  return JSON.stringify({ scripts }, null, 2)
}

// claude 2026-10-20: created: a minimal Nuxt app's package.json, pinned to the tested Nuxt and to this package's version (ADR-0009, decision 8)
/** Renders the `package.json` of a new Nuxt app. */
export function render({ npmName, version }) {
  return `${JSON.stringify({
    name: npmName,
    type: 'module',
    private: true,
    packageManager: 'pnpm@10.34.4',
    scripts: {
      ...scripts,
      preview: 'nuxt preview',
      postinstall: 'nuxt prepare',
    },
    dependencies: {
      'h3': h3Version,
      'nuxt': nuxtVersion,
      'vue': '^3.5.28',
      'vue-router': '^4.6.4',
    },
    devDependencies: {
      '@yuri4n/h34s-nuxt': version,
    },
  }, null, 2)}\n`
}
