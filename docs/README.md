# h34s documentation

<!-- claude 2026-10-20: changed: this folder is the documentation source and the site (issue #25); the AI sentence replaces the review stamp -->

This folder holds the h34s documentation and the site that renders it.
GitHub and the site show the same files: the guides and reference pages
under `content/` are the only copy. This documentation is written with AI
assistance under the maintainer's direction, and errors are tracked as
[issues](https://github.com/yuri4n/h34s/issues).

- `content/` holds the guides (`1.guide/`) and the reference pages
  (`2.reference/`). The site publishes them.
- `development.md`, `baseline.md`, `design/` and `maintainers/` are for
  the people who work on h34s. They are outside the site's content
  collection, so only GitHub shows them.
- `app/`, `nuxt.config.ts`, `remark/`, `test/` and `vendor/` are the site.

The site is separate from the Scala.js/Nuxt demo in `examples/nuxt/` and
the reusable libraries in `scala-modules/exts/`. It is a Nuxt application
built on the shared
[`@yuri4n/nuxt-docs` layer](https://github.com/yuri4n/yuri4nized-nuxt/tree/v0.1.0/layers/docs).
The repository is `yuri4nized-nuxt`; its packages use the `yuri4n.dev` name.
The Git submodule pins v0.1.0; Docus is pinned to 5.13.0 by the layer.

## Develop

Use Node 24 (at least 24.15) and pnpm 10.34.4. From the repository root:

```sh
git submodule update --init --recursive
pnpm --dir docs install --frozen-lockfile
pnpm docs:dev
```

The docs listen on port 3001. They need no Java, sbt, Scala linking, or
PostgreSQL. The repository workspace for library tooling and the demo
has its own lockfile and installation;
`pnpm install` at the repository root does not install the docs.
The site declares its own Vue and Vue Router versions. Its Nuxt
TypeScript project explicitly includes the nested shared layer, so a
clean build does not depend on the example app's generated configuration.

<!-- claude 2026-10-20: changed: the guides are the pages now; frontmatter and file names -->

Edit pages under `content/1.guide` and `content/2.reference`. Guide pages
use two-digit prefixes (`01.` to `14.`) because Nuxt Content sorts pages
by their file name as text. Give each page a `title` and a `description`
in its frontmatter: the site uses them for the page header, the
navigation, search engines and `/llms.txt`. Keep the page's `#` heading
for GitHub; the site drops it (see [Links](#links)). Docus provides
navigation, local search, code highlighting, edit links, and the mobile
menu. The landing page and theme live under `app/`. Keep source snippets
consistent with the Scala examples in `examples/nuxt/scala/`. Write setup
as instructions to application authors: define models and services,
provide their dependencies, and mount handler exports. Identify
`AppHandler` as an optional application helper, not a library export.
Describe the native layer, the Kyo core, the ZIO adapter and the optional
bridges as a toolbox, and mark planned or unverified functionality explicitly.

The shared layer disables its AI assistant, including when a deployment
injects `VERCEL_OIDC_TOKEN`. It provides `/llms.txt`, `/llms-full.txt`, raw
Markdown, and the MCP endpoint. System fonts and bundled icons avoid
external font and icon requests.

## Links

<!-- claude 2026-10-20: created: the link rule and the plugin that maps links for the site (issue #25) -->

Write each link as a relative path to the real `.md` file, with GitHub's
heading slug as the fragment, for example
`[cancellation](09.streaming.md#cancellation)` or
`[migration guide](../2.reference/4.migration.md)`. GitHub, editors and
pull request reviews follow these links as they are. Do not write site
routes such as `/guide/kyo` under `content/`.

For the site, the remark plugin `remark/md-links.mjs`, registered in
`nuxt.config.ts`, rewrites each link before Nuxt Content renders the page:

- A link to a `.md` file under `content/` becomes an absolute route:
  `03.kyo.md#fail-safely` becomes `/guide/kyo#fail-safely`.
- A link to another file of the repository becomes a GitHub URL on
  `main`, for example
  `https://github.com/yuri4n/h34s/blob/main/docs/development.md`.
- A fragment that targets a content page gets the site's heading id. The
  site adds `_` before a leading digit, so `#1-start-a-new-project`
  becomes `#_1-start-a-new-project`.
- When the frontmatter has a `title`, the plugin removes the page's first
  `#` heading, because the site shows the title above the page. Keep that
  heading for GitHub.

The plugin has these limits:

- It compares absolute POSIX paths. When a link resolves outside the
  repository, it leaves the link unchanged, and the build log shows a
  `[h34s-md-links]` warning.
- It does not map images. The content test rejects relative images under
  `content/`, so extend the plugin before you add the first image.
- GitHub URLs point at `main`, also in pull request previews.
- Nuxt Content keeps parsed pages in `.data/`. After you change the
  plugin's code, delete `.data/` before you start `pnpm docs:dev` again.

`test/md-links.test.mjs` tests the plugin. `test/content.test.mjs` checks
that every relative link in `docs/` points at an existing file and, for a
`.md` target with a fragment, at one of its headings.

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

## Deployment

<!-- claude 2026-10-20: changed: the site is deployed; production, previews and the root-directory rule -->

The site is live. The Vercel project `h34s` (team `yurian-solo`) builds
it with its Git integration, the only publisher; GitHub Actions only
check it:

- Every push to `main` deploys production, at <https://h34s.yuri4n.dev/>.
  Merging a pull request publishes its documentation changes.
- Every pull request gets a preview deployment, and the Vercel bot links
  it on the pull request.
- The project's Root Directory is `docs`, this folder. If you move or
  rename this folder, change the Root Directory in the same step.
  Otherwise every production and preview build fails, and production
  keeps serving the last successful deployment.
- `vercel.json` runs `pnpm run build`. Vercel installs with
  `pnpm install --frozen-lockfile` on Node 24, after checkout initializes
  the pinned public submodule.

<!-- claude 2026-10-20: changed: the repository visibility is an open question, not a fact (review; ADR-0003) -->

The site and the Scala packages have separate release requirements.
Building this site does not establish that h34s artifacts or the example
application are production ready. Source and edit links open
`github.com/yuri4n/h34s`, and `github.branch` in `app/app.config.ts`
targets `main`. While the repository is private, those links need access
to it. On 2026-10-20 the GitHub API reports the repository as public; the
maintainer has not confirmed the intended visibility yet
([ADR-0003](design/adr/0003-registries-stay-private.md)).

Before you merge a change to the site itself, check its preview: routes,
search, mobile navigation, light and dark themes, `/llms.txt`,
`/llms-full.txt`, and the absence of the assistant.

To reproduce the CI output locally without deploying:

```sh
cd docs
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
