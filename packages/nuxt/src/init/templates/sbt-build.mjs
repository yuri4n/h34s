// The sbt settings of the server project: build.sbt for a new sbt build (layout A), or h34s.sbt
// next to an existing build.sbt, which init never edits.
import { file, region } from './regions.mjs'

// claude 2026-10-20: changed: the release guide's resolver, a stanza of its own that init adds when the build lacks it (ADR-0009, ADR-0003, review P1)
/** The resolver lines of the release guide. */
export const resolverLines = [
  'ThisBuild / resolvers += "h34s GitHub Packages" at',
  '  "https://maven.pkg.github.com/yuri4n/h34s"',
]

// claude 2026-10-20: created: the release guide's credentials from environment variables, a stanza that init adds when the build lacks it (ADR-0009, ADR-0003, review P1)
/** The credential lines of the release guide. They name environment variables only. */
export const credentialLines = [
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

// claude 2026-10-20: created: the text that marks a build that already has GitHub Packages credentials (review P1)
/**
 * Matches an sbt build that already has credentials for GitHub Packages: the token variable that
 * the credential lines read, or a `Credentials(` call that names the host.
 */
export const credentialsPattern = /\bGITHUB_PACKAGES_TOKEN\b|\bCredentials\s*\([^)]*"maven\.pkg\.github\.com"/

// claude 2026-10-20: changed: the server project in the adoption guide's shape, the resolver and the credentials each when the build lacks it, and the commented locale exclusion (ADR-0009, review P1)
/**
 * Renders the sbt file. `sbtFile` is `build.sbt` or `h34s.sbt`; `appDir` is the Nuxt app's path
 * below the sbt build (`''` in layout A, `'web'` in layout B), where the linker writes
 * `server/.scala`. Without `resolver` or without `credentials`, the build already has that stanza
 * and init leaves it out.
 */
export function render({ api, version, sbtFile, appDir, project, scalaDir, resolver, credentials }) {
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
  if (resolver && credentials) {
    lines.push(
      '',
      '// The h34s libraries come from GitHub Packages. sbt reads your user name and token from',
      '// these environment variables. Keep the token out of this file.',
      ...region('registry', [...resolverLines, '', ...credentialLines]),
    )
  }
  else if (resolver) {
    lines.push(
      '',
      '// The h34s libraries come from GitHub Packages. Your build already has the credentials.',
      ...region('registry', resolverLines),
    )
  }
  else if (credentials) {
    lines.push(
      '',
      '// Your build already has the h34s resolver. sbt reads your GitHub Packages user name and',
      '// token from these environment variables. Keep the token out of this file.',
      ...region('registry', credentialLines),
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
