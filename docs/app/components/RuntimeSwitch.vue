<script setup lang="ts">
// claude 2026-10-20: created: the Kyo | ZIO tabs (WAI-ARIA tabs, #52)
// Automatic activation: an arrow key moves the focus and selects the tab.
// Only the selected tab is in the Tab order (roving tabindex).
import type { Runtime } from '~/composables/useRuntimeChoice'

import { runtimes } from '~/composables/useRuntimeChoice'

const props = defineProps<{
  /** Prefix of the tab and panel ids: `<idBase>-tab-kyo`, `-panel-kyo`. */
  idBase: string
  label: string
}>()

const runtime = useRuntimeChoice()
const tabs = ref<HTMLButtonElement[]>([])

function setTab(element: unknown, index: number) {
  if (element instanceof HTMLButtonElement) tabs.value[index] = element
}

function select(index: number, focus: boolean) {
  const item = runtimes[index]
  if (!item) return
  runtime.value = item.value
  if (focus) tabs.value[index]?.focus()
}

const keys: Record<string, (index: number, last: number) => number> = {
  ArrowRight: (index, last) => (index === last ? 0 : index + 1),
  ArrowLeft: (index, last) => (index === 0 ? last : index - 1),
  Home: () => 0,
  End: (_, last) => last,
}

function onKeydown(event: KeyboardEvent, index: number) {
  const move = keys[event.key]
  if (!move) return
  event.preventDefault()
  select(move(index, runtimes.length - 1), true)
}

const tabId = (value: Runtime) => `${props.idBase}-tab-${value}`
const panelId = (value: Runtime) => `${props.idBase}-panel-${value}`
</script>

<template>
  <div class="runtime-switch" role="tablist" :aria-label="label">
    <button
      v-for="(item, index) in runtimes"
      :id="tabId(item.value)"
      :key="item.value"
      :ref="(element) => setTab(element, index)"
      class="runtime-tab"
      type="button"
      role="tab"
      :aria-selected="runtime === item.value"
      :aria-controls="panelId(item.value)"
      :tabindex="runtime === item.value ? 0 : -1"
      @click="select(index, false)"
      @keydown="onKeydown($event, index)"
    >
      {{ item.label }}
    </button>
  </div>
</template>
