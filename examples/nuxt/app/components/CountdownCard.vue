<script setup lang="ts">
// claude 2026-10-20: created: one runtime's countdown, read with useCountdown (ADR-0007)
const props = defineProps<{ runtime: 'kyo' | 'zio', label: string }>()

const { remaining, state, start } = useCountdown(props.runtime)

const status = computed(() => {
  switch (state.value) {
    case 'running':
      return remaining.value === null
        ? 'Waiting for the first event…'
        : `${remaining.value} seconds left`
    case 'done':
      return 'Done: the stream ended at 0.'
    case 'failed':
      return 'The stream failed. Check the server log, then start again.'
    default:
      return 'Not started.'
  }
})
</script>

<template>
  <!-- claude 2026-10-20: created: the countdown card and its idle, running, done and failed states (ADR-0007) -->
  <section
    class="bg-card text-card-foreground flex items-center justify-between gap-4 rounded-lg border p-4"
  >
    <div class="space-y-1">
      <h2 class="font-medium">
        {{ label }}
      </h2>
      <p
        aria-live="polite"
        class="text-sm tabular-nums"
        :class="state === 'failed' ? 'text-destructive' : 'text-muted-foreground'"
      >
        {{ status }}
      </p>
    </div>
    <button
      type="button"
      class="bg-primary text-primary-foreground min-h-10 rounded-md px-4 text-sm font-medium transition-[scale,opacity] duration-150 active:scale-[0.96] disabled:opacity-50"
      :disabled="state === 'running'"
      @click="start"
    >
      {{ state === 'idle' ? 'Start' : 'Start again' }}
    </button>
  </section>
</template>
