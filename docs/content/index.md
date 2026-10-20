---
title: h34s
description: Write Nuxt server routes in Scala.js, on a Kyo core with a first-class ZIO adapter, optional schema and storage bridges, and build tooling.
navigation: false
---

# Scala handlers. Nuxt applications.

<!-- claude 2026-10-20: changed: the per-page AI stamp that stood here is removed (issue #25) -->

h34s is a toolbox for running Scala.js in your Nuxt/Nitro application. Its core is written in Kyo: one runtime owns the request lifecycle, cancellation, streaming, errors, configuration and application resources. Write your handlers in Kyo, or in ZIO through a first-class adapter over that core. Kyo applications never depend on ZIO; ZIO applications get Kyo through the adapter.

You write ordinary Scala: a handler is a function from the request to the response body. Configuration, storage and shared resources come from Scala APIs. Your models, services and databases stay in your application; the guides show how to plug in libraries such as kyo-sql or Skunk. Schemas are optional.

<!-- claude 2026-10-20: changed: relative .md links instead of routes (issue #25) -->

Start with [the getting-started tutorial](1.guide/01.getting-started.md), choose an [adoption path](1.guide/02.adoption.md), or read about [Kyo handlers](1.guide/03.kyo.md) and [ZIO handlers](1.guide/04.zio.md).

<!-- claude 2026-10-20: changed: relative .md links instead of routes; Releases replaces the release guide and the status page; route generation is on main (issue #25) -->

This site describes `0.2.0-alpha.1`, which is [published](https://github.com/yuri4n/h34s/releases/tag/v0.2.0-alpha.1) as private Maven libraries and [`@yuri4n/h34s-nuxt@0.2.0-alpha.1`](https://github.com/yuri4n/h34s/releases/tag/npm-v0.2.0-alpha.1). Follow [Releases](2.reference/2.releases.md) to install it; that page also records what works today. The older `0.1.0-alpha.1` libraries and `@yuri4n/h34s-nuxt@0.1.0-alpha.1` stay published with an older API; see the [migration guide](2.reference/4.migration.md). Routes declared in Scala, with typed `$fetch`, are on `main` and ship with the next release. Nix and application packaging are planned.
