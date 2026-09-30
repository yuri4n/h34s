# Recovered baseline

## Purpose and scope

This is a reviewable source checkpoint for h34s, not a verified MVP or a production release. It imports the existing ZIO-based Scala.js playground without restructuring its Scala modules or choosing the future public API.

The new Git history starts from a minimal bootstrap commit. The previous local history is not an ancestor of this history and is not being published. The original workspace, its index, and its uncommitted files remain unchanged. The earlier recovery backup remains local.

The recovered workspace was the source of this import. Earlier recovery used historical copies for the stream converter and Compose configuration; this import does not establish byte-perfect identity with every pre-rollback file.

### Included

- Existing Scala.js wrappers, native conversions, stream tests, services, Skunk groundwork, and SQL resources.
- Existing non-CV Nuxt pages, content, shared UI, and API request collection. These are retained playground material, not the final example application.
- Build configuration and lockfiles, including the ScalablyTyped npm lockfile.
- Project direction and delivery requirements, including first-class sbt/Nix integration and sbt-native-packager support.

### Excluded from every commit in the new history

- The accidental CV feature: its page, components, composables, audience/PDF helpers, and content files.
- Environment files, including the protected `.envrc`. It was not read or copied for this import.
- Local editor/setup state, machine-specific sbt memory options, and generated Metals configuration.
- Dependencies, linked Scala.js output, Nuxt output, caches, and recovery artifacts.

The import removes the four CV content collections, the PDF-only Vite polyfill configuration, and the three direct PDF-related dependencies. It preserves the `focuses` collection and the rest of the frontend. This is an exclusion from the new baseline; the original workspace is left intact.

## Validation performed

Validation used Node `v24.17.0` and pnpm `10.34.4` on macOS. These are the validation environment, not a supported-version matrix.

- `pnpm install --lockfile-only --ignore-scripts --offline`: completed. Reported existing peer-version conflicts, including ESLint 10 with plugins expecting ESLint 9, TypeScript/i18n tooling, and citty. No dependency upgrades were made to address them.
- Semantic lockfile comparison: retained direct versions, package metadata, and dependency resolutions are unchanged. The cleanup removes 90 package entries and changes eight dependency snapshots to optional-only status.
- `pnpm install --offline --frozen-lockfile --ignore-scripts`: passed; no downloads were needed. Lifecycle scripts were intentionally disabled. This uses the existing local package cache and is not proof of a clean-machine setup.
- `NUXT_TELEMETRY_DISABLED=1 CI=1 pnpm exec nuxt prepare`: completed and generated Nuxt types. The typed-router plugin reported `No routes defined. Check if your pages folder exists`. Preparation is not a frontend or server build, and does not verify route generation.
- Static source/dependency scan: no remaining CV implementation imports, collection references, or PDF-package references in the imported application and build files.
- Original-workspace comparison: the original HEAD, index, full Git status, and all 76 allowlisted source-file hashes are unchanged. The imported Scala code, tests, and retained UI are byte-identical to that workspace.
- A limited heuristic scan of the 75 text files in the import candidates found no matching service tokens, private-key markers, credential-bearing database URLs, or machine-specific home-directory paths. This is not a comprehensive secret audit.
- `git diff --cached --check` reported inherited whitespace in `api-collection/Another.bru`, `content/focuses/social-networks.md`, and `scala-modules/exts/zio-migrations/README.md`. Those files are preserved unchanged. A focused whitespace check covers the edited configuration and documentation separately.

## Not verified and known limitations

- **Scala compilation and tests were not run.** `build.sbt` enables `-rewrite` with `future-migration`; compiling can modify sources. Validate with an explicitly non-rewriting configuration in a follow-up change.
- **Production linking is incomplete.** Only `fastLinkJS` is configured to emit to `server/.scala`. The current production script does not establish a fresh `fullLinkJS` output at the location consumed by the TypeScript route.
- **Scala-to-TypeScript generation is not wired up.** The scala-ts plugin is listed, but no module enables it. The existing `scala:types` script is for ScalablyTyped facades in the opposite direction.
- **Database integration was not exercised.** Compose retains a stale SQL mount under `./modules/svr/`, while the source lives under `scala-modules/svr/`. Some services are stubs. No database credentials are included.
- **The current error boundary is not public-safe.** The existing wrapper can expose exception messages and defects. The safe-error/unsafe-Throwable split is a required follow-up; do not treat this baseline as ready for public deployment.
- The example contains a duplicate `NativeConverter[Unit]` that returns the string `undefined`, rather than JavaScript undefined. Converter ownership and compatibility still need review.
- The existing migration helper and its README are recovered experimental code and notes, not a verified database integration or a required persistence abstraction.
- Kyo, the optional ZIO-over-Kyo integration, improved Nuxt imports/code generation, and Nix/native-packager support are requirements, not implemented features of this import.
- Browser, end-to-end streaming/cancellation, database writes, production builds, and deployment checks were not run.

### UI verification

The `better-ui` guidance was consulted. The scope was removing an accidental feature from the import, not redesigning retained UI. No actionable UI-polish findings in that removal-only static scope. Browser visuals, responsive layout, keyboard/pointer states, loading/empty states, and motion: **Not verified**. This does not approve the unreviewed retained UI.

## Next changes

Agree on the small handler API and a bounded first implementation slice. Address build and safety limitations through focused branches and pull requests with relevant tests and documentation. Keep the original workspace as a recovery reference rather than switching its dirty working tree onto this unrelated history.

This baseline PR must be reviewed before integration. It does not authorize merging, rewriting the old local history, changing repository visibility, or implementing the full MVP in one change.
