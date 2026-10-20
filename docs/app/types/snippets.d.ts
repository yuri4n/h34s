// claude 2026-10-20: created: types of virtual:h34s-snippets (#52)
// The module comes from docs/snippets/vite-plugin.mjs.
declare module 'virtual:h34s-snippets' {
  /** One runtime's exact text, its Shiki HTML and its source. */
  export interface SnippetVariant {
    text: string
    html: string
    /** 1-based numbers of the lines that differ from the other runtime. */
    changed: number[]
    caption: string
    file: string
  }
  export interface SnippetPair {
    kyo: SnippetVariant
    zio: SnippetVariant
  }
  export interface TourFile extends SnippetPair {
    lang: string
    /** One text for both runtimes. */
    shared: boolean
    /** The runtimes keep one structure; changed lines are highlighted. */
    compare: boolean
  }
  export interface TreeEntry {
    path: string
    /** The path when the tour shows ZIO, if the ZIO file has another name. */
    zioPath?: string
    id?: string
    directory?: boolean
    generated?: boolean
  }
  export const greeting: SnippetPair
  export const tree: TreeEntry[]
  export const files: Record<string, TourFile>
}
