// claude 2026-10-20: created: the Nuxt 4 project references (ADR-0009, decision 8)
/** Renders `tsconfig.json` with the four configs that Nuxt 4 generates in `.nuxt/`. */
export function render() {
  return `${JSON.stringify({
    files: [],
    references: [
      { path: './.nuxt/tsconfig.app.json' },
      { path: './.nuxt/tsconfig.server.json' },
      { path: './.nuxt/tsconfig.shared.json' },
      { path: './.nuxt/tsconfig.node.json' },
    ],
  }, null, 2)}\n`
}
