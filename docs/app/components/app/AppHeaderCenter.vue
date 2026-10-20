<template>
  <div class="header-center">
    <nav class="site-navigation" aria-label="Primary">
      <NuxtLink to="/guide/getting-started">Guide</NuxtLink>
      <NuxtLink to="/reference/architecture">Architecture</NuxtLink>
      <!-- claude 2026-10-20: changed: the status page merged into Releases (issue #25) -->
      <NuxtLink to="/reference/releases">Releases</NuxtLink>
    </nav>
    <UContentSearchButton
      :collapsed="false"
      variant="soft"
      class="site-search"
    />
  </div>
</template>
