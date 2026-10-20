<script setup lang="ts">
defineProps<{ open: boolean }>()
</script>

<template>
  <UButton
    :icon="open ? 'i-lucide-x' : 'i-lucide-menu'"
    :aria-label="open ? 'Close menu' : 'Open menu'"
    :aria-expanded="open"
    color="neutral"
    variant="ghost"
    class="min-h-11 min-w-11"
    square
  />
</template>
