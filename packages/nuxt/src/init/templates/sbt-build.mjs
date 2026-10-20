// The sbt settings of the server project: build.sbt for a new sbt build (layout A), or h34s.sbt
// next to an existing build.sbt, which init never edits.
import { file, region } from './regions.mjs'

// claude 2026-10-20: created: the GitHub Packages resolver and the credentials from environment variables, one block that a public registry deletes (ADR-0009, ADR-0003)
/** The resolver and credential lines of the release guide. They name environment variables only. */
export const registryLines = [
  'ThisBuild / resolvers += "h34s GitHub Packages" at',
  '  "https://maven.pkg.github.com/yuri4n/h34s"',
  '',
  'ThisBuild / credentials ++= (for {',
  '  user <- sys.env.get("GITHUB_PACKAGES_USER")',
  '  token <- sys.env.get("GITHUB_PACKAGES_TOKEN")',
  '} yield Credentials(',
  '  "GitHub Package Registry",',
  '  "maven.pkg.github.com",',
  '  user,',
  '  token,',
  ')).toSeq',
]

// claude 2026-10-20: created: the resolver's host, which marks a build that already has it (ADR-0009)
/** The text that tells whether an sbt build already has the h34s resolver. */
export const registryHost = 'maven.pkg.github.com/yuri4n/h34s'

// claude 2026-10-20: created: the server project in the adoption guide's shape, the registry block and the commented locale exclusion (ADR-0009)
/**
 * Renders the sbt file. `sbtFile` is `build.sbt` or `h34s.sbt`; `appDir` is the Nuxt app's path
 * below the sbt build (`''` in layout A, `'web'` in layout B), where the linker writes
 * `server/.scala`. Without `registry`, the build already has the resolver and init leaves it out.
 */
export function render({ api, version, sbtFile, appDir, project, scalaDir, registry }) {
  const artifact = api === 'zio' ? 'zio-h3-ext' : 'kyo-h3-ext'
  const output = [...appDir.split('/').filter(Boolean), 'server', '.scala']
    .map(part => `"${part}"`)
    .join(' / ')
  const lines = region('tour', [
    `// ${sbtFile}`,
    'import org.scalajs.linker.interface.{ModuleKind, OutputPatterns}',
    '',
    `lazy val ${project} = project.in(file("${scalaDir}"))`,
    '  .enablePlugins(ScalaJSPlugin)',
    '  .settings(',
    '    scalaVersion := "3.9.0",',
    `    libraryDependencies += "dev.yuri4n" %%% "${artifact}" % "${version}",`,
    '    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)',
    '      .withOutputPatterns(OutputPatterns.fromJSFile("%s.js"))),',
    '    Compile / fastLinkJS / scalaJSLinkerConfig ~= (_.withSourceMap(true)),',
    '    Compile / fastLinkJS / scalaJSLinkerOutputDirectory :=',
    `      (LocalRootProject / baseDirectory).value / ${output},`,
    '    Compile / fullLinkJS / scalaJSLinkerOutputDirectory :=',
    `      (LocalRootProject / baseDirectory).value / ${output},`,
    '  )',
  ])
  if (registry) {
    lines.push(
      '',
      '// The h34s libraries come from GitHub Packages. sbt reads your user name and token from',
      '// these environment variables. Keep the token out of this file.',
      ...region('registry', registryLines),
    )
  }
  lines.push(
    '',
    '// h34s runs on Kyo, which brings a full currency database through scala-java-locales. It adds',
    '// tens of MB to the linked JavaScript. If your app formats no currencies or other locales,',
    '// uncomment this line:',
    `// ${project} / excludeDependencies += ExclusionRule("io.github.cquiroz", "locales-full-currencies-db_sjs1_3")`,
  )
  return file(lines)
}
