// claude 2026-10-20: created: useNotes, typed with the generated NoteView (ADR-0014)
// The routes guide shows this region (docs/content/1.guide/02.routes.md, "Use the models in your app"): keep them equal.
// region guide-models-ts
import type { NoteView } from '#h34s/models'

/** The notes of `GET /api/notes`, typed with the generated `NoteView`. */
export function useNotes() {
  return useAsyncData<NoteView[]>('notes', () => fetchNotes())
}
// endregion guide-models-ts
