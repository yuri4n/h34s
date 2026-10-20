import { file, region } from './regions.mjs'

// claude 2026-10-20: created: the sbt version of the toolchain profile (ADR-0009)
/** The sbt version of the toolchain profile. */
export const sbtVersion = '1.11.7'

// claude 2026-10-20: created: project/build.properties of a new sbt build (ADR-0009)
/** Renders `project/build.properties`. */
export function render() {
  return file(region('tour', ['# project/build.properties', `sbt.version=${sbtVersion}`], '#'))
}
