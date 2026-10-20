import { file, region } from './regions.mjs'

// claude 2026-10-20: created: the JVM options of the verified consumer builds (ADR-0009, decision 9)
/** Renders `.jvmopts`, the JVM options that the sbt launcher reads. */
export function render() {
  return file(region('tour', ['-Xss4m', '-Xmx4g'], '#'))
}
