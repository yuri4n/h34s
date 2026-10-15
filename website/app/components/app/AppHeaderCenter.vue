<template>
  <div class="header-center">
    <nav class="site-navigation" aria-label="Primary">
      <NuxtLink to="/guide/getting-started">Guide</NuxtLink>
      <NuxtLink to="/reference/architecture">Architecture</NuxtLink>
      <NuxtLink to="/reference/status">Project status</NuxtLink>
    </nav>
    <UContentSearchButton
      :collapsed="false"
      variant="soft"
      class="site-search"
    />
  </div>
</template>
