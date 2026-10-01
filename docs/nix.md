# Nix development toolchain

The flake provides a pinned **development shell**. It does not yet provide a sandboxed production derivation or an sbt-native-packager distribution. `nix develop` runs the same sbt/pnpm pipeline as the non-Nix workflow; it does not introduce a second application build.

## Enter the shell

Prerequisites: Nix with the `nix-command` and `flakes` features enabled, Git, and network access. Nix itself is not installed by this repository.

```sh
nix develop
pnpm install --frozen-lockfile
pnpm run scala:test
pnpm build
```

See [Build and run](build.md) before starting the server. The current error boundary is unsafe for public use, and the retained HTTP streaming route has a documented runtime failure.

The shell contains:

| Tool                 | Pin                                                                                  |
| -------------------- | ------------------------------------------------------------------------------------ |
| Node                 | 24.19.0 from locked nixpkgs                                                          |
| pnpm                 | 10.34.5; must match `package.json`                                                   |
| Java                 | JDK 17 from locked nixpkgs                                                           |
| sbt                  | Locked nixpkgs launcher; the application uses 1.11.7 from `project/build.properties` |
| Native build support | Compiler/make from `mkShell`, Python 3, pkg-config, and Git                          |

`flake.lock` pins nixpkgs revision `c8f90650c15282fa8656a041bfbbd2403997a9a7` and its content hash. The initial lock metadata comes from the `nixpkgs-src` input of [devenv's lockfile at fe20b5c](https://github.com/cachix/devenv/blob/fe20b5cba7ab5e93ae73f956a8d3efc50e1753f4/flake.lock). This project depends directly on nixpkgs, **not** on devenv. Nix validates the content hash when it fetches that input.

The shell sets `JAVA_HOME` and disables Nuxt telemetry. It does not load environment files, start PostgreSQL, or embed credentials.

## Checks and platform status

```sh
nix flake check --no-update-lock-file
nix develop --no-update-lock-file --command bash scripts/ci-build.sh
```

The first command checks the locked toolchain on the current system. The second installs dependencies, runs Scala tests, checks development-to-production output replacement, builds Nuxt, and exercises the production HTTP regression test. **The HTTP test currently fails on the recovered stream adapter.** Do not interpret a successful toolchain check as a passing application test.

Flake outputs are defined for `x86_64-linux`, `aarch64-linux`, `x86_64-darwin`, and `aarch64-darwin`. These are candidate platforms, not an established support matrix. The first CI target is `x86_64-linux` on Ubuntu 24.04. The [initial clean-checkout CI run](https://github.com/yuri4n/h34s/actions/runs/36794306347) passed `nix flake check`, entered the shell, installed frontend dependencies, compiled from freshly generated facades, and passed the nine converter tests. Aggregate testing then found an undeclared runtime `h3` dependency, now declared explicitly in `package.json` at the facade's version. See the PR for subsequent results.

Nix is not installed on the local macOS x86_64 validation host, so local Nix evaluation, shell execution, and the other flake platforms are **not verified**. A passing Linux toolchain check is not a passing application or cross-platform check.

## Dependency updates

1. Update nixpkgs deliberately:

   ```sh
   nix flake update nixpkgs
   ```

2. Review the new tool versions. Keep `package.json`'s pnpm pin and engine requirement aligned with the flake's `pnpm_10`. The flake asserts that the pins match instead of silently downloading another pnpm version.
3. If frontend dependencies change, regenerate and review `pnpm-lock.yaml` with the pinned pnpm. Keep the separate ScalablyTyped npm lockfile under `scala-modules/exts/h3/` under review too.
4. Run the checks above and inspect the Git diff. Commit the lock and any justified version changes together. Do not run a blanket dependency upgrade to fix an unrelated warning.

If native npm dependencies fail after switching between Nix and non-Nix toolchains, use separate clean checkouts or reinstall dependencies with the selected toolchain. Native binaries are not necessarily interchangeable across OS/architecture combinations.

## Production packaging is a separate milestone

A locked development shell does not make Maven/npm downloads reproducible or available inside a Nix sandbox. The next packaging work must explicitly capture both dependency graphs, remove incidental build-time downloads such as remote fonts, and exclude secrets and mutable data from source closures.

The intended pipeline remains: required generation and Scala.js production linking, then Nuxt/Nitro, then packaging. sbt-native-packager will package the Node application, not a JVM server, and must reuse this pipeline. Native-packager support, sandboxed `nix build`, package smoke tests, Skunk writes, cancellation checks, and deployment support are **not implemented by this change**.
