// Calls to the Scala handlers. Their response types come from the `.d.ts` files that
// `h34s-scala` writes next to the linked modules, so `$fetch` is typed without hand-written
// interfaces. Errors use `{ errors: [{ message, path? }] }`: read them from `FetchError.data`.

/** The Kyo greeting: `GET /api/kyo/greeting`, or `POST` with a name. */
export async function fetchGreeting(name?: string): Promise<string> {
  const { data } = name === undefined
    ? await $fetch('/api/kyo/greeting')
    : await $fetch('/api/kyo/greeting', { method: 'POST', body: { name } })
  return data.message
}

/** The ZIO notes: `GET /api/notes`. Needs PostgreSQL. */
export async function fetchNotes(): Promise<{ id: number, text: string }[]> {
  const { data } = await $fetch('/api/notes')
  return data
}
