// Type checks of the generated route declarations, run by `scripts/check-route-types.mjs` after
// linking. Each `@ts-expect-error` must stay an error: the types are precise, not `any`.
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

  console.warn(message, wrong, extra, id, texts, stream, errors, missing, listed, badList)
}
