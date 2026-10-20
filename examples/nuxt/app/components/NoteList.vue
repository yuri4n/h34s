<script setup lang="ts">
// claude 2026-10-20: created: the notes prop, typed with the generated NoteView (ADR-0014)
import type { NoteView } from '#h34s/models'

defineProps<{ notes: NoteView[] }>()
</script>

<template>
  <!-- claude 2026-10-20: created: the note list and its empty state (ADR-0014) -->
  <p v-if="notes.length === 0" class="text-muted-foreground text-sm">
    No notes yet.
  </p>
  <ul v-else class="space-y-2">
    <li
      v-for="note in notes"
      :key="note.id"
      class="bg-card text-card-foreground rounded-md border px-3 py-2"
    >
      {{ note.text }}
    </li>
  </ul>
</template>
