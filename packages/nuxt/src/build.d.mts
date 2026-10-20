export interface ScalaModule {
  /** sbt project ID; omit for the root project. */
  project?: string
  /** Disposable generated directory, relative to the application root. */
  output: string
  /**
   * Generated .js entry paths relative to output, checked after linking. Omit them when the
   * Scala handlers declare their routes: the output must then contain at least one h34s handler.
   */
  entries?: string[]
}
export interface H34sConfig {
  // claude 2026-10-20: created: the sbtRoot key (ADR-0009)
  /**
   * The directory of the sbt build, where the CLI runs sbt. A relative path is relative to the
   * config file's directory, for example `'..'` when the Nuxt app is in `web/` below the sbt
   * build. The default, `'.'`, is the config file's directory. The directory must hold `build.sbt`
   * or a `project/` directory. The outputs stay relative to the application root.
   */
  sbtRoot?: string
  modules: ScalaModule[]
}
// claude 2026-10-20: created: the config that loadConfig returns (ADR-0009)
/** A config read by `loadConfig`: `sbtRoot` is absolute, resolved against the config file's directory. */
export interface LoadedConfig extends H34sConfig {
  sbtRoot: string
}
export interface ConfigLocation {
  root?: string
  configFile?: string
}
export interface BuildOptions {
  root?: string
  // claude 2026-10-20: changed: how a relative sbtRoot resolves here (ADR-0009)
  /** A relative `sbtRoot` in a config that `loadConfig` did not read is relative to `root`. */
  config: H34sConfig
}
export function defineConfig<T extends H34sConfig>(config: T): T
export function loadConfig(options?: ConfigLocation): Promise<LoadedConfig> // claude 2026-10-20: changed: gives the absolute sbtRoot (ADR-0009)
export function verifyScalaOutputs(options: BuildOptions): void
export function buildScala(options: BuildOptions): void
export function linkScala(
  options: BuildOptions & {
    mode?: 'build' | 'dev' | 'watch'
  },
): void
export function watchScala(options: BuildOptions): Promise<void>

/** The route manifest file that `writeRoutes` writes into each output directory. */
export const manifestFile: 'h34s-routes.json'
// claude 2026-10-20: created: the models file (ADR-0014)
/**
 * The models file that `writeRoutes` writes into each output directory: one `export type` per
 * named model that the routes return. The Nuxt module maps `#h34s/models` to it.
 */
export const modelsFile: 'h34s-models.d.ts'
export interface ScalaRoute {
  /** The linked module, relative to the output directory. */
  module: string
  /** The Nitro route, for example `/api/notes/:id`. */
  route: string
  /** Lower-case HTTP method; absent for every method. */
  method?: 'get' | 'head' | 'post' | 'put' | 'patch' | 'delete' | 'options'
}
export interface RouteManifest {
  output: string
  version: 1
  routes: ScalaRoute[]
  /** The `.d.ts` files written next to the linked modules. */
  declarations: string[]
  // claude 2026-10-20: created: the models file and its names (ADR-0014)
  /** The models file of the output and the names it exports, in declaration order. */
  models: { file: 'h34s-models.d.ts', names: string[] }
}
// claude 2026-10-20: changed: also writes the models file, and fails on a model name with two structures (ADR-0014)
/**
 * Imports the linked modules of every output in a child Node process, writes a `.d.ts` next to
 * each h34s handler module, the models file (`h34s-models.d.ts`) and the route manifest of each
 * output. A route declaration imports the models it uses from the models file. Two Scala types
 * with one TypeScript name and different structures, in one output or in two, fail the
 * generation. It checks every output first: when one fails, no file of any output changes.
 */
export function writeRoutes(options: BuildOptions): Promise<RouteManifest[]>
export interface NitroRouteHandler {
  route: string
  method?: ScalaRoute['method']
  lazy: true
  handler: string
}
/** The Nitro handlers of the route manifests; outputs without a manifest are `missing`. */
export function readRoutes(options: BuildOptions): {
  handlers: NitroRouteHandler[]
  missing: string[]
}
