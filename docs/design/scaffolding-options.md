# Scaffolding the three adoption paths

Status: proposal for the maintainer, 2026-10-19. Nothing in this memo is implemented. It answers whether the manual setup in the adoption guide (`docs/adoption.md`, `website/content/1.guide/02.adoption.md`) can be scaffolded, and how. Tracking issue: #54 (sub-issue of #17).

Decisions already made by the maintainer (2026-10-19): cover all three paths (new project; Scala handlers in an existing Nuxt app; Nuxt in an existing Scala.js/sbt project); one-shot initialisation, not a regenerating step (the Nuxt module keeps owning generated glue such as routes); an agent prompt ships as a docs page whatever the mechanism. The layout is open: this memo recommends one.

Evidence is `path:line`. Paths under `node_modules` use these abbreviations: `$KIT` = `node_modules/.pnpm/@nuxt+kit@4.3.1_magicast@0.5.1/node_modules/@nuxt/kit/dist`, `$SCHEMA` = `node_modules/.pnpm/@nuxt+schema@4.3.1/node_modules/@nuxt/schema/dist`, `$CLI` = `node_modules/.pnpm/@nuxt+cli@3.33.1_@nuxt+schema@4.3.1_cac@6.7.14_magicast@0.5.1/node_modules/@nuxt/cli/dist`, `$NUXT` = the `nuxt@4.3.1` package in `node_modules/.pnpm`, `$PNPM` = `/root/.cache/node/corepack/v1/pnpm/10.34.4/dist/pnpm.cjs`. "UNVERIFIED" marks a claim that this session could not check (no internet, no runs).

## 1. Summary and recommendation

<!-- claude 2026-10-19: created: 1. Summary and recommendation -->

- **Mechanism, all three paths:** a new `h34s-scala init [directory]` command in the existing CLI of `@yuri4n/h34s-nuxt`. You run it without installing anything first: `pnpm dlx @yuri4n/h34s-nuxt@<version> init`. It finds the path from the files that exist and writes both halves (Nuxt and sbt).
- **Path 1** creates a minimal Nuxt 4 app, pinned to a supported Nuxt version, plus the Scala build. **Path 2** adds the Scala build at the Nuxt root and merges into `package.json`, `nuxt.config.ts`, `.npmrc` and `.gitignore`. **Path 3** creates the Nuxt app in `web/` under the sbt root and adds an `h34s.sbt` file. It never edits the existing `build.sbt`.
- **Layout:** A (`build.sbt` at the Nuxt root, Scala sources in `scala-server/`) for paths 1 and 2. This is what the guide's snippet already encodes. B (Nuxt app below the sbt root) for path 3, with one small CLI change: an `sbtRoot` key in `h34s.config.mjs` replaces the example's second, rebased config.
- **One-shot:** per project. Init creates missing files only, never overwrites one, and does nothing when `h34s.config.mjs` exists. There is no marker file and no `.nuxtrc` entry.
- **Also ship:** the agent prompt page (same rules; it runs `init` when available), and a clearer module error: `Missing h34s config: … Run h34s-scala init`.
- **Forced by:** (a) one config file works only when the sbt root and the Nuxt root are the same directory. (b) `onInstall` runs on the first Nuxt boot of each checkout, only after the module is installed and listed, so it cannot serve paths 1 and 3. The way to reach it, `nuxi module add`, is expected to fail with the documented `.npmrc`. (c) Every mechanism needs a credential before it can download itself. The existing CLI needs only one (npm), runs without Nuxt or sbt, and can write both halves.

## 2. Facts that constrain the design

<!-- claude 2026-10-19: created: 2. Facts that constrain the design -->

**2.1 The CLI and the module share one root rule.** The CLI resolves the config as `resolve(root, configFile)` and throws `Missing h34s config: <path>` when the file is absent (`packages/nuxt/src/build.mjs:85-93`). An `output` must be relative and contain no `..` segment (`build.mjs:17-32`). The CLI resolves outputs against `root` (`build.mjs:95-112`) and runs sbt with `cwd: root` (`build.mjs:162-167`). The only flags are `--root` and `--config` (`packages/nuxt/src/cli.mjs:24-36`). The Nuxt module reads the same file from `nuxt.options.rootDir` (`packages/nuxt/src/module.mjs:9-11`). The development loader rejects an output outside that root (`packages/nuxt/src/scala-dev-modules.mjs:29-42`). So the output must lie inside both roots: **with one config file, the sbt root must be the Nuxt root.** The example works around this with a second config that rebases each output by `examples/nuxt/` (`examples/nuxt/h34s.build.config.mjs:4-11`), and with root scripts that pass it (`package.json:16-20`). A sibling sbt build (layout C) cannot be driven today. PR 15 does not change these rules (`feat/generated-routes:packages/nuxt/src/build.mjs:170,206` still use `cwd: root`).

**2.2 The guide's snippet is layout A, but its prose is not.** The snippet declares `project.in(file("scala-server"))` and links into `(LocalRootProject / baseDirectory).value / "server" / ".scala"` (`website/content/1.guide/02.adoption.md:67-78`, `docs/adoption.md:91-102`). That works only when `build.sbt` sits at the Nuxt root. The prose says "Add an sbt build next to it" and "for example in `scala-server/`" (`02.adoption.md:31,41`; `docs/adoption.md:48,58`).

**2.3 `onInstall` exists, but it is a first-boot hook, not an install hook.** The types are `onInstall?: (nuxt: Nuxt) => Awaitable<void>` (`$SCHEMA/index.d.mts:310,319`); kit copies it onto the module (`$KIT/index.mjs:176`). `callLifecycleHooks` (`$KIT/index.mjs:683-699`):

- returns unless `meta.name` and `meta.version` are set (`:684`);
- reads `setups.<name>` from `<rootDir>/.nuxtrc` (`:686-689`) and calls `onInstall(nuxt)` when the entry is missing (`:691`);
- writes the entry after the hook returns, even when the hook chose to skip (`:693-696`). Only a throw prevents the write; the error is logged and the hook retries on the next boot (`:697-698`).

Kit calls it before the module's `setup` (`$KIT/index.mjs:548,581`). Nuxt calls `installModules` on every boot: dev, build, prepare (`$NUXT/dist/index.mjs:6033`). The hook receives only `nuxt`, not module options. This repository ignores `.nuxtrc` (`.gitignore:29`), so here the hook is "once per checkout". Whether consumers ignore it is UNVERIFIED.

**2.4 The h34s module cannot receive `onInstall` today.** It is a plain function and says it has no Nuxt kit dependency (`module.mjs:6-8`). The package has no `dependencies`, only an optional `nuxt` peer (`packages/nuxt/package.json:22-23`). Kit returns a plain function unchanged (`$KIT/index.mjs:623-627`) and reads `meta` only through `getMeta` (`$KIT/index.mjs:522`), so the hook is skipped at `:684`. Prior art: `@nuxt/test-utils` 4.0.0 runs a setup wizard from `onInstall` (`dist/module.mjs:970-972`). It skips in CI, without a TTY (`:472`) and in a monorepo (`:475-476`), and those skips still consume the one-shot (2.3). `@nuxt/eslint` 1.15.1 instead writes `eslint.config.mjs` from `setup` when no config exists (`dist/chunks/index.mjs:121-157`, opt-out `autoInit` at `:327,354-355`).

**2.5 `nuxi module add` reads the npm token literally and warns "not a Nuxt module".** It fetches the packument itself (`$CLI/add-I_kOiJXU.mjs:313-321`). It takes the token from `<process.cwd()>/.npmrc`, then `~/.npmrc` (`:341-343`), with a regex and `.trim()`, without environment expansion (`:345-361`). The documented `.npmrc` line is `//npm.pkg.github.com/:_authToken=${GITHUB_PACKAGES_TOKEN}` (`docs/npm-tooling.md:15-18`). So the placeholder is sent as written, the fetch fails ("Failed to fetch package details", `:319`), and the command exits 1 with "No modules to add." (`:182-185`). This is an inference from the code; nobody ran it. Unless the published packument lists `nuxt`, `nuxt-edge` or `@nuxt/kit` in `dependencies` or `devDependencies`, it warns "It seems that … is not a Nuxt module" and asks to continue, default No (`:326-332`). Our `package.json` lists none of them. After adding, it runs `nuxi prepare` (`:191`), which boots the module (2.6).

**2.6 The module stops Nuxt without its config.** `module.mjs:10` calls `loadConfig`, which throws `Missing h34s config` (`build.mjs:90-91`). Any scaffolder must write `h34s.config.mjs` before it registers the module. `onInstall` would run before this throw (2.3), but a bare `nuxi module add` ends in a failing `prepare` (inference).

**2.7 No mechanism can create the credential that downloads it.** npm needs `@yuri4n:registry=https://npm.pkg.github.com` plus a token line (`docs/npm-tooling.md:13-18`). sbt needs the GitHub Packages resolver and `GITHUB_PACKAGES_USER`/`GITHUB_PACKAGES_TOKEN` credentials (`docs/releases.md:23-37`). `pnpm create @scope/name` runs `@scope/create-name` through `dlx` (`$PNPM:202186-202198`), so a private initializer needs the npm token first. giget, used by `nuxi init -t`, reads only `GIGET_AUTH` (`giget@3.1.2/dist/_chunks/giget.mjs:191`), and `nuxi init` passes no auth (`$CLI/init-DNOKWPLa.mjs:254-260`), so that path needs a GitHub token in another variable. `sbt new` downloads `sbt-giter8-resolver` 0.18.0 at run time and needs git access to the template (report 01, section 5a; giter8 behaviour UNVERIFIED offline). An sbt plugin needs the Maven credentials in `project/` before it can download.

**2.8 The repository already generates the two halves separately.** `scripts/check-consumers.mjs:279-316` writes fresh sbt consumers: `.jvmopts`, `project/build.properties`, `project/plugins.sbt`, and a root-project `build.sbt` with `externalResolvers :=` and credentials from `GITHUB_ACTOR`/`GITHUB_TOKEN` (`:297-303`). They link CommonJS `%s.cjs` into `out/` (`:307`), not into Nuxt. `scripts/check-npm-nuxt.mjs:143-194` writes a fresh Nuxt consumer (`nuxt.config.mjs`, `h34s.config.mjs`, `app/app.vue`) with hand-written "linked" JavaScript and no sbt. Nothing builds a fresh Nuxt app with real sbt and published packages: "has not been verified yet, on any path" (`docs/adoption.md:159`). The guide's `build.sbt` omits the resolver and credentials (`02.adoption.md:63-80` against `docs/releases.md:25-37`).

**2.9 PR 15 changes what a scaffold writes.** PR #15 (`feat/generated-routes`, head `e17db7b`) is open and mergeable (`gh api repos/yuri4n/h34s/pulls/15`). Handlers declare their route with `.at(Route.get("/api/hello"))` (`feat/generated-routes:website/content/1.guide/02.adoption.md:84-118`). `entries` becomes optional (`feat/generated-routes:packages/nuxt/src/build.d.mts:6-10`). After each link, the CLI writes `h34s-routes.json` and one `.d.ts` per handler into the output (`routes.mjs:8,120-166`). It prints `[h34s] 1 route in server/.scala` (`cli.mjs:45-48`) and fails when an output has no handlers (`routes.mjs:132-133`). The PR deletes the example's five `server/api/*.ts` route files. On `main`, a scaffold must write one route file per export and keep `entries` in sync; on PR 15 it writes neither.

**2.10 Layout-relevant Nuxt and tooling facts.**

- Nuxt 4.3.1 watches only the app directory (`srcDir`) and `server/` (`$NUXT/dist/index.mjs:7380-7392`). `srcDir` is `<root>/app` when that directory exists (`$SCHEMA/index.mjs:271-275`). So sbt's `target/`, `project/` and `scala-server/` at the Nuxt root are not watched in the Nuxt 4 layout. In a Nuxt-3-style app without `app/`, `srcDir` is the root and the watcher covers `target/` too (derived from code, not run).
- Dev source maps embed Scala source text only for files inside the Nuxt root (`packages/nuxt/src/scala-source-maps.mjs:55-61`). Scala sources outside it keep their paths but lose embedded content.
- Nuxt sets `workspaceDir` to the furthest `pnpm-workspace.yaml`, else the closest `.git` (`$SCHEMA/index.mjs:264-269`). A Nuxt app below the git root therefore has `workspaceDir !== rootDir`, which makes a test-utils-style wizard skip ("Monorepo detected").
- `pnpm dlx <package>` runs the package's only binary (`$PNPM:202043-202044`). So `pnpm dlx @yuri4n/h34s-nuxt@<version> init` would run `h34s-scala init` without installing the package. A second binary would break this (`:202051`).

## 3. Options

<!-- claude 2026-10-19: created: 3. Options -->

Cost covers the mechanism and its unit tests for one implementer plus review. Documentation and the end-to-end check are counted once, in section 7.

| Mechanism                                                                      | Owner: who runs it, when                                                                                 | Paths                                                                                                           | Runs before credentials?                                                                                                         | One-shot semantics                                                                 | Writes                                                                                  | Cost                                                                                             | DX value                                                                                                                                       |
| ------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| Agent prompt page                                                              | The author's coding assistant, on request                                                                | 1, 2, 3                                                                                                         | Yes. It tells the author to create them and writes only env references                                                           | Whatever the prompt's detection rules say; not enforced                            | Both halves, by hand                                                                    | S, 0.5-1 d                                                                                       | Medium: works today, but results vary by assistant and nothing tests them                                                                      |
| `onInstall`, with `@nuxt/kit` (`defineNuxtModule`)                             | Nuxt, on the first boot (dev, build, prepare) of each checkout, after the module is installed and listed | 2 only. Paths 1 and 3 have no Nuxt app yet, and a Nuxt app below the git root triggers the monorepo skip (2.10) | No: npm token first. Its usual entry, `nuxi module add`, fails with the documented `.npmrc` (2.5)                                | Per checkout (`.nuxtrc`). A skip in CI or without a TTY still marks it done (2.3)  | Nuxt files; could write `build.sbt` at `rootDir`                                        | M, 2-4 d, plus a runtime dependency                                                              | Low: it fires at a surprising time, once per clone, and its entry command is broken for private packages                                       |
| `onInstall`, without kit (hand-attached `getMeta` and `onInstall`)             | Same                                                                                                     | 2 only                                                                                                          | Same                                                                                                                             | Same                                                                               | Same                                                                                    | M, 2-3 d                                                                                         | Low: same, and it relies on kit's internal module shape (2.4)                                                                                  |
| File-exists scaffolding in `setup` (the `@nuxt/eslint` pattern)                | Nuxt, on every boot, including CI and `postinstall: nuxt prepare`                                        | 2 only                                                                                                          | No                                                                                                                               | Per project (file exists), no prompts                                              | Anything under `rootDir`                                                                | S-M, 1-2 d                                                                                       | Low: it writes an sbt build during a boot without asking. The module throws first unless reordered (2.6). Use it only for the error hint       |
| **`h34s-scala init` in the existing CLI**                                      | The author, explicitly, once: `pnpm dlx @yuri4n/h34s-nuxt@<v> init`                                      | 1, 2, 3                                                                                                         | npm token: needed to download. Maven: not needed (it writes env references). GitHub repo token: not needed                       | Per project: it creates missing files only and stops when `h34s.config.mjs` exists | Both halves                                                                             | M, 4-6 d                                                                                         | High: one command for every path, testable from a clean directory, versions tied to the package's own version                                  |
| `create-*` initializer (`pnpm create @yuri4n/h34s` runs `@yuri4n/create-h34s`) | The author, once                                                                                         | 1 natural; 2 and 3 through flags                                                                                | No: npm token first, because the package is private                                                                              | New directory                                                                      | Both halves                                                                             | M: init plus 1-2 d for a second package and its publish job                                      | High for path 1 only when public. While private it is `pnpm dlx … init` with one more package to release in lockstep                           |
| `nuxi init -t gh:yuri4n/h34s/templates/<x>#<tag>` (giget)                      | The author, once                                                                                         | 1 only                                                                                                          | Needs `GIGET_AUTH` with read access to the private repo, a second token type. Adding `-M @yuri4n/h34s-nuxt` then hits 2.5        | New directory; giget refuses a non-empty one                                       | Both halves, copied verbatim. No variables, so one template per API and no package name | S-M, 1-3 d                                                                                       | Medium-low: an extra token, the whole repository tarball per run, no parameters                                                                |
| `sbt new` with a giter8 template                                               | The author, once                                                                                         | 1 only                                                                                                          | Needs git access to the template repo and Maven Central for the resolver. Maven credentials are needed only at the first compile | New directory                                                                      | Both halves, with parameters (UNVERIFIED offline)                                       | M, 3-5 d                                                                                         | Medium: familiar to Scala users, but it needs a separate `.g8` repo or subdirectory, git auth, and its versions can drift from the npm package |
| sbt plugin task (for example `h34sInit`)                                       | The author, after adding `addSbtPlugin`, the resolver and credentials to `project/`                      | 3 only                                                                                                          | No: Maven credentials in the meta-build first                                                                                    | Per project (file exists)                                                          | Both halves                                                                             | L, 6-10 d: a new published sbt 1.x plugin on Scala 2.12, with scripted tests and release changes | Low-medium: the setup lines it needs almost equal the lines it saves                                                                           |
| **Combination (recommended)**                                                  | `init` + prompt page + module error hint + `sbtRoot` key                                                 | 1, 2, 3                                                                                                         | npm token only                                                                                                                   | Per project                                                                        | Both halves                                                                             | M, 6-9 d                                                                                         | High                                                                                                                                           |

## 4. Layout recommendation

<!-- claude 2026-10-19: created: 4. Layout recommendation -->

|                             | A: `build.sbt` at the Nuxt root, `scala-server/` subproject                                                                                                                              | B: Nuxt app below the sbt root (`<sbt>/web/`)                                                                                                                                                                                                             | C: sibling builds (`<repo>/scala-server/build.sbt`, `<repo>/web/`)                     |
| --------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| What init writes            | `build.sbt`, `project/`, `scala-server/src/main/scala/…`, one `h34s.config.mjs`                                                                                                          | Today: two configs (`h34s.config.mjs` plus a rebased `h34s.build.config.mjs`) and scripts with `--root .. --config web/h34s.build.config.mjs` (allowed by `cli.mjs:26`, `build.mjs:89`; no test runs it). With `sbtRoot`: one config with `sbtRoot: '..'` | Not drivable today (2.1). With `sbtRoot`: one config with `sbtRoot: '../scala-server'` |
| IDE import                  | One folder holds both builds: Metals and IntelliJ see `build.sbt` at the workspace root, and Nuxt tooling sees `package.json` there (UNVERIFIED; no IDE docs or tests in the repository) | Open the sbt root; the Nuxt app is a nested package (UNVERIFIED)                                                                                                                                                                                          | Two roots, or a multi-root workspace (UNVERIFIED)                                      |
| `.gitignore`                | One file: Nuxt entries plus `.metals/`, `.bsp/`, `target/`, `project/target/`, `project/project/`, `project/metals.sbt`, `server/.scala/` (precedent `.gitignore:32-41`)                 | The existing sbt `.gitignore` plus `web/.gitignore` with Nuxt entries and `server/.scala/`                                                                                                                                                                | One per build                                                                          |
| `h34s-scala dev`            | `pnpm dev:scala`, no flags                                                                                                                                                               | Today: cross-directory flags. With `sbtRoot`: same as A                                                                                                                                                                                                   | With `sbtRoot`: same as A                                                              |
| Nuxt app below the git root | No (unless the app itself sits in a monorepo)                                                                                                                                            | Yes: `workspaceDir !== rootDir` (2.10). This matters only for an `onInstall` wizard, not for `init`                                                                                                                                                       | Yes                                                                                    |
| Nuxt watcher                | sbt directories are outside `app/` and `server/`, so not watched (2.10)                                                                                                                  | Not watched                                                                                                                                                                                                                                               | Not watched                                                                            |
| Dev source maps             | Scala sources are inside the Nuxt root, so their text is embedded                                                                                                                        | Sources outside `web/`: paths only, unless the module also embeds from `sbtRoot`                                                                                                                                                                          | Paths only, same remedy                                                                |

**Recommendation.**

- **Paths 1 and 2: layout A.** It needs no CLI change, it is what the guide's snippet already encodes (2.2), and it mirrors the only fresh Nuxt consumer the repository runs (`scripts/check-npm-nuxt.mjs:152-162`). Keep the guide's names: directory `scala-server/`, sbt project ID `server`. Fix the guide's prose ("next to it") in the same change.
- **Path 3: layout B, with the Nuxt app in `web/`.** An existing Scala repository keeps its root and its IDE import unchanged. Init adds an `h34s.sbt` next to `build.sbt`; sbt loads every `*.sbt` file in the build root (standard sbt behaviour, UNVERIFIED in this repository). If you prefer, `init .` in the sbt root gives layout A instead, with no CLI change.
- **Add the `sbtRoot` key: yes.** The change is small (S, about 1 day). The CLI runs sbt in `resolve(root, sbtRoot)` and keeps resolving and deleting outputs inside the app root, so deletion safety does not change. It removes the second config and the cross-directory flags for B, makes C work, and lets the example drop `examples/nuxt/h34s.build.config.mjs` (`sbtRoot: '../..'`). The module can optionally embed sources from `sbtRoot` as well. Without the key, init can still write B's two-config arrangement; it just costs one more file and an untested flag combination.

## 5. The recommended flow, per path

<!-- claude 2026-10-19: created: 5. The recommended flow, per path -->

`<version>` below is the first release of `@yuri4n/h34s-nuxt` that ships `init`; `0.2.0-alpha.1` does not have it. Shapes below assume PR 15 is merged (question 3). On `main`, init also writes `server/api/hello.get.ts` (`export { default } from '../.scala/hello-get.js'`) and `entries: ['hello-get.js']`.

**Before you start (once per machine).** Install JDK 25, sbt 1.11.7, Node 24 and pnpm 10. Create a classic GitHub personal access token with `read:packages` from an account that can read `yuri4n/h34s` (`docs/releases.md:21`). Set two variables from your shell profile or secret manager. Do not put them in a project file:

```sh
export GITHUB_PACKAGES_USER=<your GitHub user name>
export GITHUB_PACKAGES_TOKEN=<your classic token with read:packages>
```

Add the scope to `~/.npmrc`, so `pnpm dlx` can download the private package before any project exists. The line holds a reference, not the token:

```ini
@yuri4n:registry=https://npm.pkg.github.com
//npm.pkg.github.com/:_authToken=${GITHUB_PACKAGES_TOKEN}
```

pnpm expands `${…}` in `.npmrc` (standard behaviour; UNVERIFIED in pnpm's source here). Do not run `nuxi module add @yuri4n/h34s-nuxt`: it reads the token literally (2.5).

**What `init` does on every path.**

1. It asks three questions when it has a TTY: the effect API (`Kyo`, the default, or `ZIO`), the Scala package (default: the directory name in lower case, letters and digits only), and the Scala directory (default `scala-server`). On path 3 it also asks for the sbt project ID (default `server`). It refuses an ID that a root `*.sbt` or `project/*.scala` file already defines (text search). Without a TTY it needs `--yes` or the flags `--api`, `--package`, `--scala-dir`, `--project`. `--dry-run` prints the plan and writes nothing.
2. It detects the path, builds the complete plan, and checks for conflicts **before** it writes anything. A conflict stops the run with no changes.
3. It writes missing files and appends missing lines. It never overwrites a file. It writes `h34s.config.mjs` before it adds the module to `nuxt.config.ts` (2.6).
4. It checks whether `GITHUB_PACKAGES_USER` and `GITHUB_PACKAGES_TOKEN` are set; it never prints their values. If one is missing, it prints the export lines above and continues.
5. It prints a summary (created, edited, skipped) and the next commands. It does not install packages or run sbt, so it stays fast and works offline. Each later step fails with its own tool's message.
6. It pins versions from its own `package.json`: `"dev.yuri4n" %%% "kyo-h3-ext" % "<own version>"` (or `zio-h3-ext`) and `@yuri4n/h34s-nuxt@<own version>`. That keeps the npm/Maven pair valid (`docs/releases.md:9`).

**Detection rules.** "App directory" is the `[directory]` argument, or the current directory. The first matching row wins.

| Condition                                                                    | Result                                                                                |
| ---------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| `h34s.config.mjs` exists in the app directory                                | "Already set up." Exit 0, no changes. This is the one-shot rule                       |
| App directory is missing or empty, and the current directory has `build.sbt` | Path 3, layout B (`sbtRoot: '..'`)                                                    |
| App directory is missing or empty                                            | Path 1, layout A                                                                      |
| `nuxt.config.{ts,mts,js,mjs}` exists                                         | Path 2, layout A. If `build.sbt` also exists, write `h34s.sbt` instead of `build.sbt` |
| `build.sbt` exists in the app directory                                      | Path 3 at the sbt root (layout A)                                                     |
| Anything else (a non-empty directory with neither file)                      | Stop and explain                                                                      |

Conflicts that stop the run: `server/.scala` exists and is not empty, because a production build deletes it (`build.mjs:145-148`). `server/api/hello.get.ts` or `server/api/hello.ts` exists. `project/*.sbt` declares a different `sbt-scalajs` version than 1.22.0. Warnings that do not stop it: a Nuxt version outside `4.3.1 || 4.4.2`, `sbt.version` other than 1.11.7, a `nitro.preset` other than Node, `@yuri4n/h34s-nuxt` already listed at another version.

**Path 1: a new project.**

```sh
pnpm dlx @yuri4n/h34s-nuxt@<version> init my-app
cd my-app
pnpm install
pnpm dev:scala
curl -s http://127.0.0.1:3000/api/hello
```

The link step prints `[h34s] 1 route in server/.scala`. Expected body: `{"data":{"message":"Hello from Scala"}}`. Files created:

```text
my-app/package.json            scripts, nuxt pinned, h3@1.15.4, @yuri4n/h34s-nuxt@<v>, packageManager pnpm@10.34.4
my-app/pnpm-workspace.yaml     onlyBuiltDependencies: esbuild, @parcel/watcher (precedent pnpm-workspace.yaml:5-12)
my-app/nuxt.config.ts          modules: ['@yuri4n/h34s-nuxt'], compatibilityDate, nitro.preset 'node-server'
my-app/tsconfig.json           Nuxt 4 starter shape (UNVERIFIED offline)
my-app/app/app.vue
my-app/h34s.config.mjs         { project: 'server', output: 'server/.scala' }
my-app/.npmrc                  the two lines above, with the env reference
my-app/.gitignore              Nuxt entries + sbt entries + server/.scala/
my-app/.jvmopts                -Xss4m -Xmx4g (question 9)
my-app/build.sbt               resolver + credentials (env) + server project (ESModule, %s.js, source maps, server/.scala)
my-app/project/build.properties  sbt.version=1.11.7
my-app/project/plugins.sbt     sbt-scalajs 1.22.0
my-app/scala-server/src/main/scala/<package>/Hello.scala
```

**Path 2: an existing Nuxt app.** Run the same command in the Nuxt root without a directory, then `pnpm install`, `pnpm dev:scala` and the same request. Files created: `h34s.config.mjs`, `build.sbt` (or `h34s.sbt`), `project/build.properties`, `project/plugins.sbt`, `.jvmopts`, `scala-server/src/main/scala/<package>/Hello.scala`. Files edited (append or merge only; the summary lists each one):

- `package.json`: adds `@yuri4n/h34s-nuxt` (dev) and `h3@1.15.4` when they are missing. Adds the scripts `scala:fast`, `scala:watch`, `scala:full`, `dev:scala` when they are missing. It rewrites `build` only when it is exactly `nuxt build`, and `dev` only when it is exactly `nuxt dev`. Otherwise it prints the change to make: a production build must link first, so stale output is never packaged.
- `nuxt.config.ts`: adds `'@yuri4n/h34s-nuxt'` with a text edit when the file has exactly one `modules: [` or one `defineNuxtConfig({`. Otherwise it prints the line to add.
- `.npmrc` and `.gitignore`: appends the missing lines.

**Path 3: an existing sbt build.** In the sbt root:

```sh
pnpm dlx @yuri4n/h34s-nuxt@<version> init web
cd web
pnpm install
pnpm dev:scala
curl -s http://127.0.0.1:3000/api/hello
```

Files created: the Nuxt files of path 1 inside `web/` (with `sbtRoot: '..'` in `web/h34s.config.mjs`), plus `h34s.sbt` at the sbt root (resolver, credentials, and the server project linking into `web/server/.scala`), and `scala-server/src/main/scala/<package>/Hello.scala`. It writes `project/scalajs.sbt` only when no `project/*.sbt` declares `sbt-scalajs`, and `.jvmopts` only when it is missing. It never edits `build.sbt`, `project/plugins.sbt` or `project/build.properties`. To use your existing modules, add `.dependsOn(LocalProject("<id>"))` in `h34s.sbt`; they must cross-build for Scala.js with Scala 3.9.0 (`docs/adoption.md:71`). Whether a reference by ID works across `.sbt` files is standard sbt behaviour, UNVERIFIED here.

**The sbt credential block** (from `docs/releases.md:26-37`, written verbatim; it reads environment variables only):

```scala
ThisBuild / resolvers += "h34s GitHub Packages" at
  "https://maven.pkg.github.com/yuri4n/h34s"

ThisBuild / credentials ++= (for {
  user <- sys.env.get("GITHUB_PACKAGES_USER")
  token <- sys.env.get("GITHUB_PACKAGES_TOKEN")
} yield Credentials("GitHub Package Registry", "maven.pkg.github.com", user, token)).toSeq
```

For CI, init prints the Actions mapping from `docs/releases.md:44-48`: `GITHUB_PACKAGES_TOKEN: ${{ secrets.PACKAGES_READ_TOKEN }}` and `GITHUB_PACKAGES_USER: ${{ vars.PACKAGES_USER }}`.

## 6. The agent prompt page

<!-- claude 2026-10-19: created: 6. The agent prompt page -->

A draft of the page's content. Ship it under `docs/` and on the website next to the adoption guide. It can ship before `init` exists: rule 3 is then skipped. Replace `<version>` with the release version when publishing the page.

```markdown
---
title: Set up h34s with a coding assistant
description: A prompt that a coding assistant follows to add h34s to your project.
---

Copy the prompt below into your coding assistant. It performs the same setup as `h34s-scala init`.
Create your GitHub Packages token and set the variables from "Before you start" first.

> You are adding h34s (Scala.js server handlers for Nuxt/Nitro) to this project. Follow these rules exactly.
>
> 1. Ask me three things first: the effect API (Kyo or ZIO; default Kyo), the Scala package
>    (default: the directory name in lower case), and the Scala directory (default `scala-server`).
> 2. Never write, print or commit a token. Use only environment-variable references:
>    `${GITHUB_PACKAGES_TOKEN}` in `.npmrc`, and `sys.env.get("GITHUB_PACKAGES_USER")` and
>    `sys.env.get("GITHUB_PACKAGES_TOKEN")` in sbt. If a variable is not set, tell me its name and continue.
> 3. If `pnpm dlx @yuri4n/h34s-nuxt@<version> --help` lists `init`, run
>    `pnpm dlx @yuri4n/h34s-nuxt@<version> init` with my answers as flags. Show me its summary and stop.
> 4. Detect the path. If `h34s.config.mjs` exists, stop: the project is already set up.
>    - Empty or missing directory: a new project. Create a minimal Nuxt 4 app and an sbt build in it.
>    - `nuxt.config.*` exists: an existing Nuxt app. Add the sbt build at the Nuxt root.
>    - `build.sbt` exists and no `nuxt.config.*`: an existing sbt build. Create the Nuxt app in `web/`
>      and put the h34s settings in a new `h34s.sbt` next to `build.sbt`.
>    - Anything else: stop and ask me.
> 5. Never overwrite a file. Create missing files. Change existing files only by adding lines or
>    entries, and list every change. Never edit an existing `build.sbt`.
> 6. Write `h34s.config.mjs` before you add `'@yuri4n/h34s-nuxt'` to `modules` in `nuxt.config.ts`.
>    The module stops Nuxt when that file is missing.
> 7. Do not run `nuxi module add @yuri4n/h34s-nuxt`. Run `pnpm add -D @yuri4n/h34s-nuxt@<version>`
>    and `pnpm add h3@1.15.4`, then edit `nuxt.config.ts` yourself.
> 8. Use the release's versions: Scala 3.9.0, `sbt-scalajs` 1.22.0, sbt 1.11.7, JDK 25, Node 24,
>    Nuxt 4.3.1 or 4.4.2, and `"dev.yuri4n" %%% "kyo-h3-ext"` (or `zio-h3-ext`) at `<version>`.
> 9. Take the sbt settings and the handler from the adoption guide ("Configure the Scala.js module",
>    "Write, mount and run a handler"). Add the resolver and credentials from the release guide.
> 10. Keep `server/.scala` for generated files only: a production build deletes it. Add it to
>     `.gitignore` with `target/`, `.bsp/`, `.metals/`, `project/target/` and `project/project/`.
> 11. Verify: run `pnpm install`, then `pnpm dev:scala`, then request `http://127.0.0.1:3000/api/hello`.
>     Expect `{"data":{"message":"Hello from Scala"}}`. Report the result and every file you created
>     or changed.

If the request fails, see the troubleshooting notes in the adoption guide.
```

Until `sbtRoot` ships, rule 4's path 3 must say instead: "Create the Nuxt app at the sbt root."

## 7. Estimate for the implementation round

<!-- claude 2026-10-19: created: 7. Estimate for the implementation round -->

One implementer, one reviewer, one fixer. Days are focused working days for one person. Agent sessions shorten the writing, but not the sbt, install and CI loops, which nobody has timed for a fresh Kyo consumer.

| Deliverable                                                                                                                                                                                                                                                                                                                                                                         | Size | Implement         | Review + fix    |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---- | ----------------- | --------------- |
| `sbtRoot` key: `defineConfig`, `linkScala`, PR 15's `watchScala`, `build.d.mts`, unit tests; optionally drop `examples/nuxt/h34s.build.config.mjs`                                                                                                                                                                                                                                  | S    | 1 d               | 0.5 d           |
| Module error hint for `Missing h34s config`                                                                                                                                                                                                                                                                                                                                         | S    | 0.25 d            | 0.1 d           |
| `h34s-scala init` core: subcommand and flags (`node:util` `parseArgs`), detection, plan, `--dry-run`, all-or-nothing write, prompts (`node:readline/promises`), summary, help text                                                                                                                                                                                                  | M    | 2-3 d             | 1 d             |
| Templates per path and per API, plus the merge rules for `package.json`, `nuxt.config.ts`, `.gitignore` and `.npmrc`; unit tests on fixture directories (`node --test`, as in `packages/nuxt/test`)                                                                                                                                                                                 | M    | 2-3 d             | 1 d             |
| Credential bootstrap: env checks, printed lines, env-reference blocks, a test that no token value reaches a written file                                                                                                                                                                                                                                                            | S    | 0.5 d             | 0.25 d          |
| End-to-end check `scripts/check-scaffold.mjs` plus CI jobs. From a clean temporary directory: packed npm tarball, real Nuxt install, real sbt. Staging Maven repository in `scala.yml` (after `releasePublishLocal`, like `check-consumers.mjs`); GitHub Packages in `release.yml` (`--published`). Then `pnpm dev:scala`, `nuxt build`, a request, and an idempotent second `init` | L    | 3-5 d             | 1-2 d           |
| Docs: adoption guide (`docs/adoption.md`, website `02.adoption.md`), getting-started (website `01`, `README.md` quickstart), npm tooling (`docs/npm-tooling.md`, website `13`, `packages/nuxt/README.md`, CLI help), `README.md:74`, reference pages (status, architecture, compatibility), homepage cards (`website/app/pages/index.vue:28-45`) and `website/content/index.md:17`  | M    | 2-3 d             | 1 d             |
| Agent prompt page (docs and website)                                                                                                                                                                                                                                                                                                                                                | S    | 0.5 d             | 0.25 d          |
| **Total**                                                                                                                                                                                                                                                                                                                                                                           |      | **about 11-16 d** | **about 5-6 d** |

Assumptions: PR 15 merges first; the registries stay private; pnpm is the only tested package manager; `init` adds no runtime dependencies; the end-to-end check pins Nuxt 4.3.1 (the only Nuxt in the lockfile) and runs one Kyo app per path plus one ZIO link, to bound CI time; CI runners match `scala.yml` (JDK 25, sbt 1.11.7). The cloud sessions cannot read the private registry (`docs/maintainers/cloud.md:252`), so the published-mode check runs only in GitHub Actions or on the maintainer's Mac.

Suggested pull requests, each reviewable alone: (1) the prompt page, describing today's manual setup; (2) `sbtRoot` and the error hint; (3) `init` with templates and unit tests; (4) the end-to-end check; (5) the docs switch to `init` (with or after 3, because docs must not describe unimplemented commands).

## 8. Questions for the maintainer

<!-- claude 2026-10-19: created: 8. Questions for the maintainer -->

1. **Public initializer or private scope?** Options: (a) no new package; `pnpm dlx @yuri4n/h34s-nuxt@<v> init`. (b) a private `@yuri4n/create-h34s` (`pnpm create @yuri4n/h34s`). (c) a public unscoped `create-h34s` on npmjs, which conflicts with "keep packages private". **Recommendation: (a).** (b) adds a package with the same credential prerequisite. Revisit (c) with question 7.
2. **Add `@nuxt/kit` (or any dependency) to the module?** Options: (a) no; `init` uses Node built-ins and the module stays a plain function. (b) kit for `defineNuxtModule` and `onInstall`. (c) `magicast` for robust `nuxt.config` edits. **Recommendation: (a).** `onInstall` is not in the plan, and the text edit falls back to a printed instruction. Note: if `nuxi module add` ever becomes a supported path, a `nuxt` or `@nuxt/kit` devDependency also silences the "not a Nuxt module" prompt (2.5).
3. **Base on `main` or on PR 15?** Options: (a) merge PR 15 first, so templates have no route files and no `entries`. (b) build on `main` now and rework later. **Recommendation: (a).** Your rule "the module owns generated glue such as routes" is PR 15's model; on `main` the scaffold would write route files that you then maintain by hand. This also needs your pending scala-ts versus PR 15 decision (`docs/design/kyo-core.md:39,65`).
4. **One-shot per project or per checkout?** Options: (a) per project: the committed `h34s.config.mjs` is the signal, with no marker file. (b) per checkout: `.nuxtrc` (Nuxt's `onInstall` record). **Recommendation: (a).** (b) re-runs on every fresh clone and in CI, and it records a skip as done (2.3).
5. **pnpm only, or npm and Yarn too?** Options: (a) pnpm only. (b) pnpm tested; scripts that call `h34s-scala` and `node node_modules/nuxt/bin/nuxt.mjs` directly, so they work under npm, untested; Yarn Plug'n'Play unsupported. (c) all three tested. **Recommendation: (b).** It costs nothing extra and claims nothing untested.
6. **Layout C and the CLI.** Options: (a) keep C unsupported and write B's two-config arrangement. (b) add the `sbtRoot` key. **Recommendation: (b)**, about 1 day (section 4).
7. **Do the registries stay private?** This couples with issue #21 (nuxt4s umbrella, new Maven group, repository rename). While private, every mechanism needs a token first. Issue #21 also notes that GitHub Packages requires the npm scope to match the repository owner, so `@yuri4n` stays while private (UNVERIFIED here). Options: (a) private for this round. (b) public under the new group and scope before the scaffold ships. **Recommendation: (a).** Keep the registry lines in one template block, so going public deletes lines rather than flows. Publish anything public once, under the final names, and never under `@yuri4n` first and then renamed.
8. **Path 1's Nuxt half.** Options: (a) `init` writes a minimal Nuxt app pinned to a supported version (`4.3.1 || 4.4.2`, `packages/nuxt/package.json:22`). (b) delegate to Nuxt's starter, then run `init` as path 2. Which Nuxt version the starter installs is UNVERIFIED. **Recommendation: (a)**, and document (b) as the alternative for authors who want Nuxt's starter.
9. **Template defaults.** Proposed: Kyo as the default API; write `.jvmopts` (`-Xss4m -Xmx4g`, the only verified consumer setup, `scripts/check-consumers.mjs:282-283`); `nitro.preset: 'node-server'` (only Node is supported, `docs/architecture.md:80`); the Kyo locale exclusion (`docs/kyo.md:206-214`) as a commented line, not active; no `NODE_OPTIONS` heap setting. Keep the guide's names (`scala-server/`, project `server`) and the command name `init`. **Recommendation: accept as proposed**, or name what to change.

## 9. Evidence appendix

<!-- claude 2026-10-19: created: 9. Evidence appendix -->

Research reports (session scratch space; they can disappear, so the key citations are repeated below):

- `/tmp/claude-0/-home-user-h34s/526a028b-0f90-59b0-bf76-773e7169b572/scratchpad/scaffold/01-mechanisms.md`: Nuxt kit `onInstall`, `nuxi module add`, `nuxi init` and giget, `create-*` initializers, `sbt new` and giter8, the private registries.
- `/tmp/claude-0/-home-user-h34s/526a028b-0f90-59b0-bf76-773e7169b572/scratchpad/scaffold/02-consumer-setup.md`: what a consumer must produce per path, detection signals, layout constraints, registry steps, the docs surface.

Citations re-checked in this session:

- h34s CLI and module: `packages/nuxt/src/cli.mjs:5-12,24-38`; `packages/nuxt/src/build.mjs:17-32,85-93,95-112,145-148,162-167`; `packages/nuxt/src/module.mjs:6-11`; `packages/nuxt/src/scala-dev-modules.mjs:29-42`; `packages/nuxt/src/scala-source-maps.mjs:55-61`; `packages/nuxt/package.json:15,22-23`.
- Example workaround: `examples/nuxt/h34s.config.mjs:3-16`, `examples/nuxt/h34s.build.config.mjs:4-11`, `package.json:16-20`, `examples/nuxt/package.json:11-15`.
- Guide and registry docs: `website/content/1.guide/02.adoption.md:28-33,41,63-80,120,124`; `docs/adoption.md:47-48,58,91-102,159`; `docs/npm-tooling.md:13-18,111,117-128,136`; `docs/releases.md:9,21-52`; `.gitignore:29,32-41`; `pnpm-workspace.yaml:5-12`.
- Nuxt kit and schema 4.3.1: `$KIT/index.mjs:176,522,548,581,623-627,683-699`; `$SCHEMA/index.d.mts:310,319`; `$SCHEMA/index.mjs:264-269,271-275`; `$NUXT/dist/index.mjs:6033,7229-7238,7380-7392`.
- Nuxt CLI 3.33.1: `$CLI/add-I_kOiJXU.mjs:182-185,191,313-321,326-332,341-361`; `$CLI/init-DNOKWPLa.mjs:58,254-260`.
- Prior art: `@nuxt/test-utils` 4.0.0 `dist/module.mjs:472,475-476,970-972`; `@nuxt/eslint` 1.15.1 `dist/chunks/index.mjs:121-157,327,354-355`.
- giget 3.1.2 `dist/_chunks/giget.mjs:115-126,190-191`; pnpm 10.34.4 `$PNPM:202035-202056` (dlx binary choice), `:202186-202198` (`create`).
- Generators: `scripts/check-consumers.mjs:18-26,279-316`; `scripts/check-npm-nuxt.mjs:139-194`; CI: `.github/workflows/scala.yml:105-107`, `.github/workflows/release.yml:41-64`.
- PR 15: `gh api repos/yuri4n/h34s/pulls/15` (open, head `e17db7b`, mergeable "clean"); `feat/generated-routes:packages/nuxt/src/build.d.mts:1-11`, `routes.mjs:8,132-133`, `module.mjs:114`, `cli.mjs:40-49`, `examples/nuxt/h34s.config.mjs`; `git diff --stat origin/main...feat/generated-routes` (five route files deleted).
- Issue #21 body and comments (`gh api repos/yuri4n/h34s/issues/21`): group id and repository rename still open.

UNVERIFIED (no network, nothing run): `nuxi module add` failing with the placeholder token; pnpm's `${VAR}` expansion and `~/.npmrc` lookup for `dlx`; whether npm or pnpm fail when the variable is unset in a project `.npmrc`; `npx` picking a single binary; Nuxt starter contents (`tsconfig.json`, `.gitignore`, `.nuxtrc`) and the Nuxt version it installs; `onInstall` in Nuxt 4.4.2; giter8 features and private-repo auth; GitHub Packages setting `latest` for an alpha-only package; whether the repository's own `GITHUB_TOKEN` reads its npm package; sbt loading every root `*.sbt` and `project/*.sbt` and cross-file `LocalProject` references; IDE import for layouts A, B and C; the two-config B arrangement driven with `--root .. --config`; the time a fresh Kyo link takes in CI; `yurian.land`'s layout (not reachable from this session).
