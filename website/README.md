# h34s documentation website

AI-assisted draft. Human review pending.

This is a separate Nuxt application built on the shared
[`@yuri4n/nuxt-docs` layer](https://github.com/yuri4n/yuri4nized-nuxt/tree/v0.1.0/layers/docs).
The repository is `yuri4nized-nuxt`; its packages use the `yuri4n.dev` name.
The Git submodule pins v0.1.0; Docus is pinned to 5.13.0 by the layer.

## Develop

Use Node 24 (at least 24.15) and pnpm 10.34.4. From the repository root:

```sh
git submodule update --init --recursive
pnpm --dir website install --frozen-lockfile
pnpm docs:dev
```

The docs listen on port 3001. They need no Java, sbt, Scala linking, or
PostgreSQL. The root application has its own lockfile and installation;
`pnpm install` at the repository root does not install the docs.
The website declares its own Vue and Vue Router versions. Its Nuxt
TypeScript project explicitly includes the nested shared layer, so a
clean build does not depend on the example app's generated configuration.

Edit pages under `content/1.guide` and `content/2.reference`. Docus provides
navigation, local search, code highlighting, edit links, and the mobile
menu. The landing page and theme live under `app/`. Keep source snippets
consistent with the Scala example. Mark planned functionality explicitly.

The shared layer disables its AI assistant, including when a deployment
injects `VERCEL_OIDC_TOKEN`. It provides `/llms.txt`, `/llms-full.txt`, raw
Markdown, and the MCP endpoint. System fonts and bundled icons avoid
external font and icon requests.

## Validate and build

```sh
pnpm docs:check
pnpm docs:build
pnpm docs:preview
```

The check runs the shared ESLint preset, TypeScript, internal page-link
checks, and a check that the landing example matches real Scala source.
The pinned Docus source has up to ten known TypeScript diagnostics (eight
in the prepared workspace, ten in a fresh checkout and CI).
`scripts/typecheck.mjs` prints them and allows only their exact upstream
file locations and error codes. New diagnostics, application/shared-layer
errors, signals, and unexpected exits fail the command. This is a bounded
upstream exception, not a clean upstream typecheck.

The default production output is a Node server in `.output/`. Start it
with Node 24. The docs CI uses the shared v0.1.0 workflow and builds the
Vercel server preset; it checks the generated agent files and verifies
that the assistant remains disabled. The workflow never deploys.
The CI runner defaults the build's Node heap limit to 4 GiB for
prerendering. It preserves other `NODE_OPTIONS` flags and explicit heap
limits.

## Publish after review

The website and Scala packages have separate release requirements.
Building this site does not establish that h34s artifacts or the example
application are production ready. Nothing in this change deploys a site.

For a future Vercel project:

1. Review the content and repository visibility policy. h34s is currently
   private, while a public docs deployment would expose its documentation
   and examples. Source/edit links require repository access.
2. Choose `website` as the project Root Directory, Nuxt as the framework,
   Node 24, and `pnpm install --frozen-lockfile` as the install command.
   Ensure checkout initializes the pinned public submodule before install.
3. Use the single Vercel Git integration as the publisher. Keep GitHub
   Actions as checks only. `vercel.json` runs `pnpm run build`.
4. Set `NUXT_SITE_URL` to the agreed absolute production URL. Vercel's
   production URL is a fallback; local builds use the layer's localhost
   fallback. Check canonical and `/llms.txt` links on the preview.
5. Set `github.branch` in `app/app.config.ts` to the reviewed source branch
   when promoting the docs. It currently points at the documentation PR.
6. Check preview routes, search, mobile navigation, light/dark themes,
   `/llms-full.txt`, and the absence of the assistant before publishing.

To reproduce the CI output locally without deploying:

```sh
cd website
NITRO_PRESET=vercel VERCEL_OIDC_TOKEN=ci-assistant-check pnpm ci
```

The Vercel preset writes `.vercel/output`. That generated directory is
ignored. Do not commit deployment credentials or project-link metadata.
The site pins its Vercel function runtime to Node 24 and uses Vercel's
image service for that preset, avoiding bundled IPX/Sharp binaries.

## Update the shared layer

Check out an explicit reviewed tag in `vendor/yuri4n.dev`, update this
workspace's lockfile, run the checks and builds, then commit the changed
Git submodule pointer. Update the reusable CI workflow tag at the same
time. Do not edit the vendor checkout or track its moving main branch.
