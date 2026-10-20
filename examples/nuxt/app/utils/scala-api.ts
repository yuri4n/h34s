// claude 2026-10-20: created: the generated note model (ADR-0014)
import type { NoteView } from '#h34s/models'

// claude 2026-10-20: changed: the file comment names #h34s/models (ADR-0014)
// Calls to the Scala handlers. Their response types come from the `.d.ts` files that
// `h34s-scala` writes next to the linked modules, so `$fetch` is typed without hand-written
// interfaces. The models they return, such as `NoteView`, come from `#h34s/models`.
// Errors use `{ errors: [{ message, path? }] }`: read them from `FetchError.data`.

/** The Kyo greeting: `GET /api/kyo/greeting`, or `POST` with a name. */
export async function fetchGreeting(name?: string): Promise<string> {
  const { data } = name === undefined
    ? await $fetch('/api/kyo/greeting')
    : await $fetch('/api/kyo/greeting', { method: 'POST', body: { name } })
  return data.message
}

// claude 2026-10-20: changed: returns the generated NoteView instead of a hand-written shape (ADR-0014)
/** The ZIO notes: `GET /api/notes`. Needs PostgreSQL. */
export async function fetchNotes(): Promise<NoteView[]> {
  const { data } = await $fetch('/api/notes')
  return data
}
