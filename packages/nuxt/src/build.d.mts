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
  modules: ScalaModule[]
}
export interface ConfigLocation {
  root?: string
  configFile?: string
}
export interface BuildOptions {
  root?: string
  config: H34sConfig
}
export function defineConfig<T extends H34sConfig>(config: T): T
export function loadConfig(options?: ConfigLocation): Promise<H34sConfig>
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
}
// claude 2026-10-19: changed: states that a failure leaves every output's files as they were
/**
 * Imports the linked modules of every output in a child Node process, writes a `.d.ts` next to
 * each h34s handler module and the route manifest of each output. It checks every output first:
 * when one fails, no file of any output changes.
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
