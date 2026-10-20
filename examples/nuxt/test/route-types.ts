// Type checks of the generated route declarations, run by `scripts/check-route-types.mjs` after
// linking. Each `@ts-expect-error` must stay an error: the types are precise, not `any`.
// claude 2026-10-20: created: the models of the routes (ADR-0014)
import type { Greeting, NoteView } from '#h34s/models'
import type listNotes from '../server/.scala/notes-get.js'
import type { ErrorBody } from '../server/.scala/notes-post'

/** A route file that re-exports the linked module (`export { default } from ...`) gets this type. */
type ListBody = Awaited<ReturnType<typeof listNotes>>

export async function checks(): Promise<void> {
  const greeting = await $fetch('/api/kyo/greeting')
  const message: string = greeting.data.message
  // @ts-expect-error the message is a string
  const wrong: number = greeting.data.message
  // @ts-expect-error the body has only `data`
  const extra = greeting.message

  const created = await $fetch('/api/notes', { method: 'POST', body: { text: 'hi' } })
  const id: number = created.data.id
  const notes = await $fetch('/api/notes')
  const texts: string[] = notes.data.map(note => note.text)

  // A stream is sent as text.
  const stream: string = await $fetch('/api/test')
  // claude 2026-10-20: created: an event stream is sent as text too (ADR-0007)
  const events: string = await $fetch('/api/kyo/countdown')
  const zioEvents: string = await $fetch('/api/zio/countdown')

  const errors: ErrorBody = { errors: [{ message: 'must have at least 1 character', path: 'text' }] }
  // @ts-expect-error an entry needs a message
  const missing: ErrorBody = { errors: [{ path: 'text' }] }

  // The error body, as the routes guide reads it.
  await $fetch('/api/notes', {
    method: 'POST',
    body: { text: '' },
    onResponseError({ response }) {
      const body: ErrorBody = response._data
      console.warn(body.errors.map(entry => entry.path ?? entry.message))
    },
  })

  const listed: ListBody = { data: [{ id: 1, text: 'a' }] }
  // @ts-expect-error ids are numbers
  const badList: ListBody = { data: [{ id: '1', text: 'a' }] }

  // claude 2026-10-20: created: the models from #h34s/models and the useNotes composable (ADR-0014)
  // The models from `#h34s/models` are the types that the routes return.
  const fetched: NoteView[] = notes.data
  const first: NoteView = created.data
  const hello: Greeting = greeting.data
  // @ts-expect-error a NoteView has a text
  const noText: NoteView = { id: 1 }
  const { data: loaded } = await useNotes()
  const list: NoteView[] | undefined = loaded.value

  // claude 2026-10-20: changed: also the event streams (ADR-0007)
  console.warn(message, wrong, extra, id, texts, stream, events, zioEvents, errors, missing, listed, badList)
  console.warn(fetched, first, hello, noText, list)
}
