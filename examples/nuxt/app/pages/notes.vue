<script setup lang="ts">
// claude 2026-10-20: created: the notes page reads GET /api/notes through useNotes (ADR-0014)
const { data: notes, error, status } = await useNotes()
</script>

<template>
  <!-- claude 2026-10-20: created: the loading, error and list states of the notes page (ADR-0014) -->
  <main class="mx-auto max-w-xl space-y-4 p-6">
    <h1 class="text-2xl font-semibold">
      Notes
    </h1>
    <p v-if="status === 'pending'" class="text-muted-foreground text-sm">
      Loading notes…
    </p>
    <p v-else-if="error" role="alert" class="text-destructive text-sm">
      The notes could not be loaded. Check that PostgreSQL is running, then reload the page.
    </p>
    <NoteList v-else :notes="notes ?? []" />
  </main>
</template>
