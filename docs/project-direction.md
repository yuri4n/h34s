# Project direction: nuxt4s

The intended future name is **nuxt4s**: a toolbox for writing Scala.js server code in Nuxt/Nitro. h3 is the transport integration within that toolbox. The project also includes independent ZIO and Kyo handler APIs, optional data/conversion/storage integrations, and npm build/development tooling.

The current repository, documentation, packages, Scala namespaces, commands, and configuration still use **h34s**. No rename or publication happens as part of recording this direction. Continue using the documented h34s installation commands until a reviewed migration supplies replacements.

## Keep application choices in the application

Choose the modules you need, define your models and services, and compose their environments outside endpoint code. Keep database configuration, authorization, resource ownership, and deployment policy in your application. A helper used while dogfooding becomes a library API when it has a reusable boundary and independent validation; application-specific services stay in the application.

For `yurian.land`, the user chose native Kyo APIs. Evaluate Kyo SQL directly for that application's PostgreSQL service, with compilation, database behavior, and resource cleanup validated before claiming support. Keep Cats Effect interoperability optional for existing Cats libraries. The ZIO/Skunk example is an optional application recipe using Cats resource interoperability; it does not choose the database API for Kyo consumers.

## Plan the coordinated rename

Review these surfaces together before changing the public identity:

| Surface | Migration decisions and checks |
| --- | --- |
| GitHub repository and links | Choose the repository name, check redirects and private access, and update checkout URLs, source links, badges, workflows, and downstream references. Preserve existing release tags and history. |
| Maven packages | Decide organization and artifact names separately from the repository name. Check GitHub registry URLs and credentials, staged POMs, independent consumers, and immutable published versions. |
| Scala namespaces | Decide whether package/import names need to change. Assess source compatibility, forwarding aliases or deprecations, generated facade namespaces, and a documented migration path. |
| npm tooling | Decide the package name, CLI name, configuration filename, module imports, and runtime/environment key names. Test packed archives and external Nuxt consumers with any compatibility aliases. |
| Documentation and applications | Update the site identity and URLs, metadata, guides, examples, dependency installation, and downstream applications together. Keep examples and the documentation app distinct from the toolbox. |
| Release and compatibility policy | Document coexistence or deprecation of old coordinates, select new versions, verify migration commands and supported toolchains, and publish reviewed release notes without replacing existing artifacts. |

A repository rename does not automatically require a Maven organization, artifact, or Scala package rename. Make those choices explicitly and test the resulting consumer experience before releasing them.
