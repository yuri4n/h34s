<script setup lang="ts">
definePageMeta({ layout: 'default' })
useSeoMeta({
  title: 'Scala handlers for Nuxt',
  description:
    'Use the h34s toolbox to run Scala.js in your Nuxt app. Choose ZIO or Kyo handlers and provide your own services.',
})

const example = `@JSExportTopLevel("default", "notes-get")
val listNotes = AppHandler.define:
  ZIO.serviceWithZIO[Notes](_.list)`
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
    title: 'Handlers & environments',
    description: 'Keep endpoint code small. Compose services in your app.',
    to: '/guide/handlers',
    tag: 'ZIO',
  },
  {
    icon: 'i-lucide-layers',
    title: 'Kyo handlers & services',
    description: 'Use native Kyo effects and resolve services in your app.',
    to: '/guide/kyo',
    tag: 'KYO',
  },
  {
    icon: 'i-lucide-database',
    title: 'PostgreSQL with Skunk',
    description:
      'Configure an optional database in your app with typed queries.',
    to: '/guide/database',
    tag: 'DATABASE',
  },
  {
    icon: 'i-lucide-waves',
    title: 'Streams & cleanup',
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
        <NuxtLink class="preview-note" to="/reference/status">
          <span class="status-dot" aria-hidden="true" />
          Published alpha
          <UIcon name="i-lucide-arrow-up-right" aria-hidden="true" />
        </NuxtLink>
        <h1 id="hero-title">
          Scala handlers.<br /><span>Nuxt applications.</span>
        </h1>
        <p class="hero-description">
          Use h34s to run Scala.js in your Nuxt app. Choose ZIO or Kyo handlers,
          define your services, and provide their dependencies in your app.
        </p>
        <div class="hero-actions">
          <UButton
            to="/guide/getting-started"
            size="xl"
            trailing-icon="i-lucide-arrow-right"
          >
            Get started
          </UButton>
          <UButton
            to="/guide/handlers"
            size="xl"
            color="neutral"
            variant="outline"
          >
            Explore the handler API
          </UButton>
        </div>
        <p class="hero-footnote">
          Install the Maven libraries and npm tooling. The repository demo is
          optional.
        </p>
      </div>

      <div class="code-scene">
        <div class="code-card">
          <div class="code-card-header">
            <span
              ><span class="scala-file-mark" aria-hidden="true">≡</span>
              NotesHandler.scala</span
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
          ><code><span class="syntax-annotation">@JSExportTopLevel</span>(<span class="syntax-string">"default"</span>, <span class="syntax-string">"notes-get"</span>)
<span class="syntax-keyword">val</span> listNotes = <span class="syntax-type">AppHandler</span>.define:
  <span class="syntax-type">ZIO</span>.serviceWithZIO[<span class="syntax-type">Notes</span>](_.list)</code></pre>
          <div class="code-scope">
            <UIcon name="i-lucide-layers" aria-hidden="true" /> Define
            AppHandler to provide your services
          </div>
          <div class="response-example">
            <div class="response-label">
              <span>GET</span> /api/notes
              <span class="response-shape">response shape</span>
            </div>
            <pre><code>[{ "id": 1, "text": "Hello from Scala" }]</code></pre>
          </div>
          <p v-if="copied || copyError" class="copy-feedback" role="status">
            {{ copyError || 'Handler copied to clipboard.' }}
          </p>
        </div>
        <div class="code-caption">
          <span class="caption-rule" /> AppHandler and Notes are your
          application code.
        </div>
      </div>
    </section>

    <div
      class="technology-line"
      aria-label="Scala.js with h3 and Nuxt; choose ZIO or Kyo. Skunk is an optional example."
    >
      <span class="technology-label">Choose your integrations.</span>
      <span>Scala.js</span><span>ZIO or</span><span>Kyo</span><span>h3</span
      ><span>Nuxt</span><span>Skunk example</span>
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
        <p>
          Private Maven libraries and npm tooling are published. Choose ZIO or
          Kyo handlers and configure your own services. ZIO Schema decoding is
          optional; the demo shows Skunk database access. Automatic routes and
          production packaging remain planned.
        </p>
        <NuxtLink class="text-link" to="/reference/status"
          >What works today
          <UIcon name="i-lucide-arrow-right" aria-hidden="true"
        /></NuxtLink>
      </div>
    </section>
    <p class="home-review-note">
      <span class="ai-slop">AI SLOP</span> Written with Codex. Human review
      pending.
    </p>
  </div>
</template>
