---
title: h34s
description: Use Scala.js in your Nuxt app with ZIO or Kyo handlers, optional adapters, and build tooling.
navigation: false
---

# Scala handlers. Nuxt applications.

[AI SLOP]{.ai-slop} Written with Codex. Human review pending.

h34s is a toolbox for running Scala.js in your Nuxt/Nitro application. Choose native h3 bindings, ZIO handlers, or Kyo handlers, then add optional adapters and npm build tooling as needed. Define your models and services in your application, provide their dependencies, and mount the linked handlers as Nuxt routes.

Use the ZIO API for request decoding, responses, and scoped streaming, or choose the independent Kyo API for Kyo programs. For a database, configure Skunk and PostgreSQL in your application; the Notes example shows one way to do this. h34s does not create your application or configure its database.

Start with [the getting-started guide](/guide/getting-started), [adopt h34s in an existing project](/guide/adoption), or explore [ZIO handlers](/guide/handlers) and [Kyo handlers](/guide/kyo).

ZIO handlers run on ZIO's runtime; Kyo handlers run on Kyo's runtime. Private Maven libraries are published under `dev.yuri4n` at `0.1.0-alpha.1`. The separate [npm build tooling](/guide/npm-tooling) publishes the shared CLI and Nuxt module as `@yuri4n/h34s-nuxt@0.1.0-alpha.1`. Follow the [release installation guide](https://github.com/yuri4n/h34s/blob/main/docs/releases.md). Automatic route and type generation, Nix, and application packaging remain planned; [project status](/reference/status) records the current limits.
