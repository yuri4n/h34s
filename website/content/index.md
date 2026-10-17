---
title: h34s
description: Write Nuxt server routes in Scala.js, on a Kyo core with a first-class ZIO adapter, optional schema and storage bridges, and build tooling.
navigation: false
---

# Scala handlers. Nuxt applications.

[AI SLOP]{.ai-slop} Written with AI assistance. Human review pending.

h34s is a toolbox for running Scala.js in your Nuxt/Nitro application. Its core is written in Kyo: one runtime owns the request lifecycle, cancellation, streaming, errors, configuration and application resources. Write your handlers in Kyo, or in ZIO through a first-class adapter over that core. Kyo applications never depend on ZIO; ZIO applications get Kyo through the adapter.

You write ordinary Scala: a handler is a function from the request to the response body. Configuration, storage and shared resources come from Scala APIs. Your models, services and databases stay in your application; the guides show how to plug in libraries such as kyo-sql or Skunk. Schemas are optional.

Start with [the getting-started tutorial](/guide/getting-started), choose an [adoption path](/guide/adoption), or read about [Kyo handlers](/guide/kyo) and [ZIO handlers](/guide/zio).

This site describes the unreleased `0.2.0-SNAPSHOT` from the `feat/kyo-core` branch. The published `0.1.0-alpha.1` libraries and `@yuri4n/h34s-nuxt@0.1.0-alpha.1` have an older API; see the [migration guide](/reference/migration). Route generation, Nix and application packaging are planned; [status](/reference/status) records what works today.
