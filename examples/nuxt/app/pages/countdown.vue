<template>
  <!-- claude 2026-10-20: created: the countdown page (ADR-0007) -->
  <main class="mx-auto max-w-xl space-y-4 p-6">
    <h1 class="text-2xl font-semibold">
      Countdown
    </h1>
    <p class="text-muted-foreground text-sm">
      Each card reads server-sent events from a Scala handler with the
      browser's EventSource: 3, 2, 1 and 0, one per second.
    </p>
    <CountdownCard runtime="kyo" label="Kyo: GET /api/kyo/countdown" />
    <CountdownCard runtime="zio" label="ZIO: GET /api/zio/countdown" />
  </main>
</template>
