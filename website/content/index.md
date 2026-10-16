---
title: h34s
description: Scala handlers for Nuxt, with application-owned services and native JavaScript responses.
navigation: false
---

# Scala handlers. Nuxt applications.

[AI SLOP]{.ai-slop} Written with Codex. Human review pending.

h34s is a Scala.js toolbox for Nuxt/Nitro with a published `0.1.0-alpha.1` Maven alpha. Its ZIO API offers ordinary ZIO handlers, application-owned service layers, optional schema decoding, Skunk database access, scoped streaming, and Scala source maps.

Start with [the getting-started guide](/guide/getting-started), [adopt h34s in an existing project](/guide/adoption), or explore [ZIO handlers](/guide/handlers) and [Kyo handlers](/guide/kyo).

The first-class Kyo API provides native Kyo handlers and application-owned services on Kyo's own runtime. Private Maven libraries are published under `dev.yuri4n`. The separate [npm build tooling](/guide/npm-tooling) publishes the shared CLI and Nuxt module as `@yuri4n/h34s-nuxt@0.1.0-alpha.1`. Follow the [release installation guide](https://github.com/yuri4n/h34s/blob/main/docs/releases.md). Automatic route and type generation, Nix, and application packaging remain planned; [project status](/reference/status) records the current limits.
