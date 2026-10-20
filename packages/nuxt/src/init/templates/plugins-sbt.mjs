import { file, region } from './regions.mjs'

// claude 2026-10-20: created: the Scala.js version of the toolchain profile (ADR-0009)
/** The Scala.js sbt plugin version that the h34s libraries are built with. */
export const scalaJsVersion = '1.22.0'

// claude 2026-10-20: created: the line that a build without Scala.js must add (ADR-0009)
/** The line that loads the Scala.js sbt plugin. */
export const pluginLine = `addSbtPlugin("org.scala-js" % "sbt-scalajs" % "${scalaJsVersion}")`

// claude 2026-10-20: created: project/plugins.sbt of a new sbt build (ADR-0009)
/** Renders `project/plugins.sbt`. */
export function render() {
  return file(region('tour', ['// project/plugins.sbt', pluginLine]))
}
