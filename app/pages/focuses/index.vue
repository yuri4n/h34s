<script lang="ts" setup>
const { data } = await useAsyncData(
  'focuses-navigation',
  () => queryCollectionNavigation('focuses', ['icon']),
)

const children = computed(() => data.value?.at(0)?.children)

watchEffect(() => {
  console.warn('Route changed:', children.value)
})

const items = [
  {
    title: 'The Dawn of Innovation',
    description: 'Explore the birth of groundbreaking ideas and inventions.',
  },
  {
    title: 'The Digital Revolution',
    description: 'Dive into the transformative power of technology.',
  },
  {
    title: 'The Art of Design',
    description: 'Discover the beauty of thoughtful and experience design.',
  },
  {
    title: 'The Power of Communication',
    description: 'Understand the impact of effective communication in our lives.',
  },
  {
    title: 'The Pursuit of Knowledge',
    description: 'Join the quest for understanding and enlightenment.',
  },
  {
    title: 'The Joy of Creation',
    description: 'Experience the thrill of bringing ideas to life.',
  },
  {
    title: 'The Spirit of Adventure',
    description: 'Embark on exciting journeys and thrilling discoveries.',
  },
]
</script>

<template>
  <ShadBentoGrid v-if="children" class="mx-auto max-w-4xl min-w-4xl">
    <ShadBentoGridItem
      v-for="(item, index) in children"
      :key="index"
      :class="index === 3 || index === 6 ? 'md:col-span-2' : ''"
    >
      <template #header>
        <div class="animate-pulse space-x-4 relative">
          <ShadGlowBorder />
          <Icon v-if="item.icon && typeof item.icon === 'string'" :name="item.icon" size="4em" class="block size-full box-border" />
          <!-- <div class="flex size-full flex-1 rounded-md">
          </div> -->
        </div>
      </template>

      <template #title>
        <strong>{{ item.title }}</strong>
      </template>

      <template #icon />

      <template #description>
        <p>{{ item.description }}</p>
        Hi
      </template>
    </ShadBentoGridItem>
  </ShadBentoGrid>
</template>
