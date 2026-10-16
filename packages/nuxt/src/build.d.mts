export interface ScalaModule {
  /** sbt project ID; omit for the root project. */
  project?: string
  /** Disposable generated directory, relative to the application root. */
  output: string
  /** Nonempty generated .js entry paths relative to output. */
  entries: string[]
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
