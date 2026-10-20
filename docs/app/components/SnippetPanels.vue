<script setup lang="ts">
// claude 2026-10-20: created: the code of both runtimes in one grid cell (#52)
// Both panels stay in the layout, so a switch never moves the page; the
// inactive one is hidden with `visibility`, which also removes it from the
// accessibility tree and the Tab order.
import type { SnippetPair } from 'virtual:h34s-snippets'

import { describeChanged, runtimes } from '~/composables/useRuntimeChoice'

const props = defineProps<{
  /** The same prefix as the RuntimeSwitch that controls these panels. */
  idBase: string
  variants: SnippetPair
  /** The id of the element that names the file, for the panel's label. */
  labelId: string
}>()

const runtime = useRuntimeChoice()

const panels = computed(() =>
  runtimes.map((item) => ({
    ...item,
    variant: props.variants[item.value],
    description: describeChanged(
      props.variants[item.value].changed,
      item.value === 'kyo' ? 'zio' : 'kyo',
    ),
  })),
)
</script>

<template>
  <div class="snippet-stack">
    <div
      v-for="panel in panels"
      :id="`${idBase}-panel-${panel.value}`"
      :key="panel.value"
      class="snippet-panel"
      :class="{ 'is-active': runtime === panel.value }"
      role="tabpanel"
      :aria-labelledby="`${idBase}-tab-${panel.value} ${labelId}`"
      :aria-describedby="
        panel.description ? `${idBase}-changes-${panel.value}` : undefined
      "
      :aria-hidden="runtime === panel.value ? undefined : 'true'"
      :tabindex="runtime === panel.value ? 0 : -1"
    >
      <!-- eslint-disable-next-line vue/no-v-html -- build-time Shiki HTML -->
      <div class="snippet-code" v-html="panel.variant.html" />
      <p
        v-if="panel.description"
        :id="`${idBase}-changes-${panel.value}`"
        class="sr-only"
      >
        {{ panel.description }}
      </p>
    </div>
  </div>
</template>
