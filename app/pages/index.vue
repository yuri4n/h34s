<template>
  <h1 class="font-sans-serif text-2xl">
    Hi I'm Julian
    <div v-if="data">
      <code lang="json">
        <pre>
          {{ data }}
      </pre>
      </code>
    </div>
  </h1>
</template>

<script setup lang="ts">
const { data } = await useFetch('/api/scala', {
  method: 'POST',
  body: { name: 'Julian', age: 30 },
})
</script>
