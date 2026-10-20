<script setup lang="ts">
definePageMeta({ layout: 'default' })
useSeoMeta({
  title: 'Scala handlers for Nuxt',
  description:
    'Write Nuxt server routes as plain Scala.js functions. h34s runs them on a Kyo core, with a first-class ZIO adapter. Your services stay in your app.',
})

const example = `@JSExportTopLevel("default", "kyo-greeting-post")
val personalizedGreeting: EventHandler = AppHandler
  .define: request =>
    for
      input    <- request.body.json[GreetingRequest]
      greeting <- Env.use[Greetings](_.greet(input.name))
    yield greeting
  .at(Route.post("/api/kyo/greeting"))`
const copied = ref(false)
const copyError = ref('')

async function copyExample() {
  try {
    await navigator.clipboard.writeText(example)
    copied.value = true
    copyError.value = ''
  } catch {
    copyError.value = 'Select the code to copy it manually.'
  }
}

const paths = [
  {
    number: '01',
    icon: 'i-lucide-terminal',
    title: 'Start a new project',
    description:
      'Create a Nuxt app, choose your h34s modules, and add your first Scala handler.',
    to: '/guide/adoption',
    label: 'Start building',
  },
  {
    number: '02',
    icon: 'i-lucide-panels-top-left',
    title: 'Bring Scala to Nuxt',
    description:
      'Add Scala handlers alongside the TypeScript routes you already have.',
    to: '/guide/adoption',
    label: 'Explore the Nuxt path',
  },
  {
    number: '03',
    icon: 'i-lucide-braces',
    title: 'Bring Nuxt to Scala',
    description:
      'Keep your Scala.js models and services. Give them a Nuxt frontend.',
    to: '/guide/adoption',
    label: 'Explore the Scala path',
  },
]
const guides = [
  {
    icon: 'i-lucide-layers',
    title: 'Kyo handlers & services',
    description: 'Request in, body out. Wire your services once in your app.',
    to: '/guide/kyo',
    tag: 'KYO',
  },
  {
    icon: 'i-lucide-layers',
    title: 'The ZIO adapter',
    description: 'Write ordinary ZIO programs over the same core.',
    to: '/guide/zio',
    tag: 'ZIO',
  },
  {
    icon: 'i-lucide-settings',
    title: 'Configuration & lifetimes',
    description: 'Read runtime config from Scala. Share pools safely.',
    to: '/guide/configuration',
    tag: 'APP',
  },
  {
    icon: 'i-lucide-database',
    title: 'PostgreSQL, your way',
    description: 'Plug in kyo-sql or Skunk directly. No wrapper needed.',
    to: '/guide/database',
    tag: 'DATABASE',
  },
  {
    icon: 'i-lucide-waves',
    title: 'Streams & cancellation',
    description: 'Keep resources alive until a response finishes.',
    to: '/guide/streaming',
    tag: 'LIFECYCLE',
  },
  {
    icon: 'i-lucide-bug',
    title: 'Debug in Scala',
    description: 'Follow Nuxt errors back to the original Scala source.',
    to: '/guide/debugging',
    tag: 'DX',
  },
]
</script>

<template>
  <div class="home-page">
    <section class="home-hero" aria-labelledby="hero-title">
      <div class="hero-copy">
        <!-- claude 2026-10-20: changed: the status page merged into Releases (issue #25) -->
        <NuxtLink class="preview-note" to="/reference/releases">
          <span class="status-dot" aria-hidden="true" />
          Kyo-core preview
          <UIcon name="i-lucide-arrow-up-right" aria-hidden="true" />
        </NuxtLink>
        <h1 id="hero-title">
          Scala handlers.<br /><span>Nuxt applications.</span>
        </h1>
        <p class="hero-description">
          Write Nuxt server routes as plain Scala.js functions. h34s runs them
          on a Kyo core, with a first-class ZIO adapter. Your services stay in
          your app.
        </p>
        <div class="hero-actions">
          <UButton
            to="/guide/getting-started"
            size="xl"
            trailing-icon="i-lucide-arrow-right"
          >
            Get started
          </UButton>
          <UButton to="/guide/kyo" size="xl" color="neutral" variant="outline">
            Explore the handler API
          </UButton>
        </div>
        <p class="hero-footnote">
          Release 0.2.0-alpha.1 is a private alpha. The repository demo is
          optional.
        </p>
      </div>

      <div class="code-scene">
        <div class="code-card">
          <div class="code-card-header">
            <span
              ><span class="scala-file-mark" aria-hidden="true">≡</span>
              Handlers.scala</span
            >
            <button
              class="copy-code"
              type="button"
              :aria-label="copied ? 'Code copied' : 'Copy handler code'"
              @click="copyExample"
            >
              <UIcon
                :name="copied ? 'i-lucide-check' : 'i-lucide-copy'"
                aria-hidden="true"
              />
            </button>
          </div>
          <div class="code-comment">// Your application handler</div>
          <pre
            class="hero-code"
          ><code><span class="syntax-annotation">@JSExportTopLevel</span>(<span class="syntax-string">"default"</span>, <span class="syntax-string">"kyo-greeting-post"</span>)
<span class="syntax-keyword">val</span> personalizedGreeting: <span class="syntax-type">EventHandler</span> = <span class="syntax-type">AppHandler</span>
  .define: request =>
    <span class="syntax-keyword">for</span>
      input    &lt;- request.body.json[<span class="syntax-type">GreetingRequest</span>]
      greeting &lt;- <span class="syntax-type">Env</span>.use[<span class="syntax-type">Greetings</span>](_.greet(input.name))
    <span class="syntax-keyword">yield</span> greeting
  .at(<span class="syntax-type">Route</span>.post(<span class="syntax-string">"/api/kyo/greeting"</span>))</code></pre>
          <div class="code-scope">
            <UIcon name="i-lucide-layers" aria-hidden="true" /> Define
            AppHandler to provide your services
          </div>
          <div class="response-example">
            <div class="response-label">
              <span>POST</span> /api/kyo/greeting
              <span class="response-shape">response shape</span>
            </div>
            <pre><code>{ "data": { "message": "Hello, Scala!" } }</code></pre>
          </div>
          <p v-if="copied || copyError" class="copy-feedback" role="status">
            {{ copyError || 'Handler copied to clipboard.' }}
          </p>
        </div>
        <div class="code-caption">
          <span class="caption-rule" /> AppHandler, Greetings and
          GreetingRequest are your application code.
        </div>
      </div>
    </section>

    <div
      class="technology-line"
      aria-label="Scala.js on h3 and Nuxt, with a Kyo core and an optional ZIO adapter. Databases are your choice."
    >
      <span class="technology-label">Pick what you need.</span>
      <span>Scala.js</span><span>Kyo core</span><span>ZIO adapter</span
      ><span>h3</span><span>Nuxt</span><span>Your database</span>
    </div>

    <section class="home-section" aria-labelledby="paths-title">
      <div class="section-heading">
        <div>
          <p class="eyebrow">YOUR STARTING POINT</p>
          <h2 id="paths-title">Start where you are.</h2>
        </div>
        <p>
          One toolbox. Three ways in.<br />Choose the path that fits your
          application.
        </p>
      </div>
      <div class="path-grid">
        <NuxtLink
          v-for="path in paths"
          :key="path.number"
          :to="path.to"
          class="path-card"
        >
          <div class="path-card-top">
            <UIcon :name="path.icon" aria-hidden="true" /><span>{{
              path.number
            }}</span>
          </div>
          <h3>{{ path.title }}</h3>
          <p>{{ path.description }}</p>
          <span class="card-action"
            >{{ path.label }}
            <UIcon name="i-lucide-arrow-right" aria-hidden="true"
          /></span>
        </NuxtLink>
      </div>
    </section>

    <section class="home-section guide-section" aria-labelledby="guides-title">
      <div class="section-heading">
        <div>
          <p class="eyebrow">FROM REQUEST TO RESPONSE</p>
          <h2 id="guides-title">Build the whole handler.</h2>
        </div>
        <NuxtLink class="text-link" to="/reference/architecture"
          >See the architecture
          <UIcon name="i-lucide-arrow-up-right" aria-hidden="true"
        /></NuxtLink>
      </div>
      <div class="guide-grid">
        <NuxtLink
          v-for="guide in guides"
          :key="guide.to"
          :to="guide.to"
          class="guide-card"
        >
          <span class="guide-icon"
            ><UIcon :name="guide.icon" aria-hidden="true"
          /></span>
          <div>
            <span class="guide-tag">{{ guide.tag }}</span>
            <h3>{{ guide.title }}</h3>
            <p>{{ guide.description }}</p>
          </div>
          <UIcon
            class="guide-arrow"
            name="i-lucide-arrow-up-right"
            aria-hidden="true"
          />
        </NuxtLink>
      </div>
    </section>

    <section class="status-section" aria-labelledby="status-title">
      <div>
        <p class="eyebrow">SMALL STEPS. CLEAR BOUNDARIES.</p>
        <h2 id="status-title">A working foundation.<br />A clear direction.</h2>
      </div>
      <div class="status-description">
        <!-- claude 2026-10-20: changed: route generation is on main (issue #25) -->
        <p>
          0.1.0-alpha.1 has the older API. 0.2.0-alpha.1 moves the core to Kyo,
          adds a ZIO adapter, Scala configuration, shared resources and one
          schema per model. Routes declared in Scala are on main for the next
          release; packaging remains planned.
        </p>
        <!-- claude 2026-10-20: changed: the status page merged into Releases (issue #25) -->
        <NuxtLink class="text-link" to="/reference/releases"
          >What works today
          <UIcon name="i-lucide-arrow-right" aria-hidden="true"
        /></NuxtLink>
      </div>
    </section>
    <!-- claude 2026-10-20: changed: the AI stamp note that stood here is removed (issue #25) -->
  </div>
</template>
