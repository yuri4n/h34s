<script setup lang="ts">
const { data: notes, error, status } = await useNotes()
</script>

<template>
  <main class="mx-auto max-w-xl space-y-4 p-6">
    <h1 class="text-2xl font-semibold">
      Notes
    </h1>
    <p v-if="status === 'pending'" class="text-muted-foreground text-sm">
      Loading notes…
    </p>
    <p v-else-if="error" role="alert" class="text-sm">
      The notes could not be loaded. Check that PostgreSQL is running, then reload the page.
    </p>
    <NoteList v-else :notes="notes ?? []" />
  </main>
</template>
