import org.scalajs.linker.interface.{ESVersion, ModuleKind, OutputPatterns}

// Explicitly exclude aggregate projects, applications and the migration experiment.
lazy val releaseModules = Seq(
  "h3Ext", "zioH3Ext", "kyoH3Ext", "zioJsExt", "nativeConverterZIOExt",
  "nativeConverterZIOJsonExt", "nativeConverterZIOSchemaExt", "zioH3SchemaExt",
  "nuxtHubExt", "zioNuxtHubExt", "kyoNuxtHubExt",
)
addCommandAlias("releaseCheck", releaseModules.map(_ + "/makePom").mkString(";"))
addCommandAlias("releasePublishLocal", releaseModules.map(_ + "/publishLocal").mkString(";"))
addCommandAlias("releasePublish", releaseModules.map(_ + "/publish").mkString(";"))

inThisBuild(
  Seq(
    // scalaVersion      := "3.7.4",
    scalaVersion      := "3.5.2",
    organization      := "dev.yuri4n",
    homepage          := Some(url("https://github.com/yuri4n/h34s")),
    scmInfo           := Some(ScmInfo(url("https://github.com/yuri4n/h34s"), "scm:git:https://github.com/yuri4n/h34s.git")),
    publishMavenStyle := true,
    // Scala 3.5 Scaladoc currently fails reading generated inline h3 TASTy.
    // Publish the compiled library and sources; guides live in the docs site.
    Compile / packageDoc / publishArtifact := false,
    pomIncludeRepository := (_ => false),
    publishTo := Some("GitHub Packages" at "https://maven.pkg.github.com/yuri4n/h34s"),
    credentials ++= (for {
      actor <- sys.env.get("GITHUB_ACTOR").toSeq
      token <- sys.env.get("GITHUB_TOKEN").toSeq
    } yield Credentials("GitHub Package Registry", "maven.pkg.github.com", actor, token)),
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    scalacOptions ++= Seq(
      "-Wunused:all",
      "-Xkind-projector",
      "-source",
      "future-migration",
    ),
  ),
)

lazy val configureCommonJsProject: Project => Project =
  _.enablePlugins(ScalaJSPlugin)
    .settings(
      libraryDependencies ++= Seq(
        "dev.zio"       %%% "zio-interop-cats"      % "23.1.0.13",
        "dev.zio"       %%% "zio-json"              % "0.7.38",
        "dev.zio"       %%% "zio-schema-derivation" % "1.7.5",
        "dev.zio"       %%% "zio-schema-json"       % "1.7.5",
        "dev.zio"       %%% "zio-schema"            % "1.7.5",
        "dev.zio"       %%% "zio-streams"           % "2.1.23",
        "dev.zio"       %%% "zio"                   % "2.1.23",
        "eu.timepit"    %%% "refined"               % "0.11.3",
        "is.cir"        %%% "ciris-refined"         % "3.12.0",
        "is.cir"        %%% "ciris"                 % "3.12.0",
        "org.getshaka"  %%% "native-converter"      % "0.9.0",
        "org.tpolecat"  %%% "skunk-core"            % "1.0.0-M12",
        "org.tpolecat"  %%% "skunk-refined"         % "1.0.0-M12",
        "org.typelevel" %%% "cats-effect"           % "3.6.3",
        "co.fs2"        %%% "fs2-core"              % "3.12.2",
        "dev.zio"       %%% "zio-test"              % "2.1.23" % Test,
        "dev.zio"       %%% "zio-test-sbt"          % "2.1.23" % Test,
      ),
    )

lazy val root = (project in file("."))
  .aggregate(exts, nuxtsvr, kyoH3Ext, kyoExample, nuxtHubExt, zioNuxtHubExt, kyoNuxtHubExt)
  .settings(
    publish / skip := true,
  )

lazy val exts = (project in file("scala-modules/exts"))
  .settings(
    name    := "exts",
    publish / skip := true,

  )
  .dependsOn(
    // h3Ext,
    zioH3Ext,
    zioJsExt,
    zioMigrationsExt,
    nativeConverterZIOExt,
    nativeConverterZIOJsonExt,
    nativeConverterZIOSchemaExt,
    zioH3SchemaExt,
  )
  .aggregate(
    h3Ext,
    zioH3Ext,
    zioJsExt,
    zioMigrationsExt,
    nativeConverterZIOExt,
    nativeConverterZIOJsonExt,
    nativeConverterZIOSchemaExt,
    zioH3SchemaExt,
  )

lazy val h3Ext: Project = (project in file("scala-modules/exts/h3"))
  .settings(
    name    := "h3-ext",

  )
  .enablePlugins(ScalablyTypedConverterGenSourcePlugin)
  .settings(
    stOutputPackage := "dev.yuri4n.h34s.facades",
    stMinimize := Selection.AllExcept("h3"),
    libraryDependencies ++= Seq(
      "org.getshaka" %%% "native-converter" % "0.9.0",
    ),
    Compile / npmDependencies := Seq(
      "h3" -> "1.15.4",
      // "@types/node" -> "25.0.3",
    ),
    stIgnore += "@types/node",
  )

lazy val zioH3Ext: Project = (project in file("scala-modules/exts/zio-h3"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name    := "zio-h3-ext",

    libraryDependencies ++= Seq(
      "dev.zio"      %%% "zio"              % "2.1.23",
      "dev.zio"      %%% "zio-prelude"      % "1.0.0-RC41",
      "dev.zio"      %%% "zio-streams"      % "2.1.23",
      "dev.zio"      %%% "zio-json"         % "0.7.38",
      "org.getshaka" %%% "native-converter" % "0.9.0",
      "dev.zio"      %%% "zio-test"         % "2.1.23" % Test,
      "dev.zio"      %%% "zio-test-sbt"     % "2.1.23" % Test,
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)
      .withOutputPatterns(OutputPatterns.fromJSFile("%s.cjs"))),
    Test / jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(
      org.scalajs.jsenv.nodejs.NodeJSEnv.Config().withEnv(Map(
        "NODE_PATH" -> ((h3Ext / Compile / npmUpdate).value / "node_modules").getAbsolutePath,
      )),
    ),
  )
  .dependsOn(h3Ext, zioJsExt, nativeConverterZIOExt)

// Kyo RC7 macros need Java 25 at build time. Only Kyo modules use Scala 3.9.0;
// the ZIO modules keep Scala 3.5.2. The application runtime remains Node.
// Depend directly on h3Ext so Kyo consumers do not acquire ZIO or application services.
lazy val kyoH3Ext: Project = (project in file("scala-modules/exts/kyo-h3"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "kyo-h3-ext",

    scalaVersion := "3.9.0",
    libraryDependencies ++= Seq(
      "io.getkyo" %%% "kyo-core" % "1.0.0-RC7",
      "org.getshaka" %%% "native-converter" % "0.9.0",
      "org.scalameta" %%% "munit" % "1.2.1" % Test,
    ),
    Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)
      .withOutputPatterns(OutputPatterns.fromJSFile("%s.cjs"))),
    Test / jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(
      org.scalajs.jsenv.nodejs.NodeJSEnv.Config().withEnv(Map(
        "NODE_PATH" -> ((h3Ext / Compile / npmUpdate).value / "node_modules").getAbsolutePath,
      )),
    ),
  )
  .dependsOn(h3Ext)

lazy val kyoExample: Project = (project in file("scala-modules/kyo-example"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "kyo-example",

    scalaVersion := "3.9.0",
    // This English-only example uses scala-java-locales' built-in English fallback.
    // Kyo's full currency database adds tens of MB of JS; locale policy belongs
    // to the application, so the reusable kyoH3Ext dependency graph is unchanged.
    excludeDependencies += ExclusionRule("io.github.cquiroz", "locales-full-currencies-db_sjs1_3"),
    publish / skip := true,
    scalaJSUseMainModuleInitializer := false,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)
      .withOutputPatterns(OutputPatterns.fromJSFile("%s.js"))
      .withESFeatures(_.withESVersion(ESVersion.ES2021))),
    Compile / fastLinkJS / scalaJSLinkerConfig ~= (_.withSourceMap(true)),
    Compile / fastLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / ".." / ".." / "server" / ".scala-kyo",
    Compile / fullLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / ".." / ".." / "server" / ".scala-kyo",
    cleanFiles += (Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value,
  )
  .dependsOn(kyoH3Ext)

// Optional storage bindings. The native module imports neither a generated
// NuxtHub module nor an effect runtime; applications supply their configured KV.
lazy val nuxtHubExt: Project = (project in file("scala-modules/exts/nuxthub"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "nuxthub-ext",
  )

lazy val zioNuxtHubExt: Project = (project in file("scala-modules/exts/zio-nuxthub"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "zio-nuxthub-ext",
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio" % "2.1.23",
      "org.getshaka" %%% "native-converter" % "0.9.0",
      "dev.zio" %%% "zio-test" % "2.1.23" % Test,
      "dev.zio" %%% "zio-test-sbt" % "2.1.23" % Test,
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)
      .withOutputPatterns(OutputPatterns.fromJSFile("%s.cjs"))),
    Test / jsEnv := nuxtHubTestJsEnv((nuxtHubExt / baseDirectory).value),
  )
  .dependsOn(nuxtHubExt % "compile->compile;test->test")

lazy val kyoNuxtHubExt: Project = (project in file("scala-modules/exts/kyo-nuxthub"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "kyo-nuxthub-ext",
    scalaVersion := "3.9.0",
    libraryDependencies ++= Seq(
      "io.getkyo" %%% "kyo-core" % "1.0.0-RC7",
      "org.getshaka" %%% "native-converter" % "0.9.0",
      "org.scalameta" %%% "munit" % "1.2.1" % Test,
    ),
    Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)
      .withOutputPatterns(OutputPatterns.fromJSFile("%s.cjs"))),
    Test / jsEnv := nuxtHubTestJsEnv((nuxtHubExt / baseDirectory).value),
  )
  .dependsOn(nuxtHubExt % "compile->compile;test->test")

def nuxtHubTestJsEnv(moduleDirectory: File) =
  new org.scalajs.jsenv.nodejs.NodeJSEnv(
    org.scalajs.jsenv.nodejs.NodeJSEnv.Config().withEnv(Map(
      "NODE_PATH" -> sys.props.getOrElse("h34s.nuxthub.nodePath", (moduleDirectory / "test-js" / "node_modules").getAbsolutePath),
    )),
  )

lazy val zioJsExt: Project = (project in file("scala-modules/exts/zio-js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name    := "zio-js-ext",

    libraryDependencies ++= Seq(
      "dev.zio"      %%% "zio"              % "2.1.23",
      "dev.zio"      %%% "zio-streams"      % "2.1.23",
      "dev.zio"      %%% "zio-json"         % "0.7.38",
      "org.getshaka" %%% "native-converter" % "0.9.0",
    ),
  )

lazy val nativeConverterZIOExt = (project in file("scala-modules/exts/native-converter-zio"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name    := "native-converter-zio-ext",

    libraryDependencies ++= Seq(
      "dev.zio"      %%% "zio"              % "2.1.23",
      "dev.zio"      %%% "zio-prelude"      % "1.0.0-RC41",
      "dev.zio"      %%% "zio-streams"      % "2.1.23",
      "dev.zio"      %%% "zio-json"         % "0.7.38",
      "org.getshaka" %%% "native-converter" % "0.9.0",
    ),
  )

lazy val nativeConverterZIOJsonExt: Project =
  (project in file("scala-modules/exts/native-converter-zio-json"))
    .enablePlugins(ScalaJSPlugin)
    .settings(
      name    := "native-converter-zio-json-ext",

      libraryDependencies ++= Seq(
        "dev.zio"      %%% "zio-json"         % "0.7.38",
        "org.getshaka" %%% "native-converter" % "0.9.0",
      ),
    )
    .dependsOn(nativeConverterZIOExt)

// Optional schema integrations. NativeConverter-only users need neither project.
lazy val nativeConverterZIOSchemaExt: Project =
  (project in file("scala-modules/exts/native-converter-zio-schema"))
    .enablePlugins(ScalaJSPlugin)
    .settings(
      name := "native-converter-zio-schema-ext",

      libraryDependencies ++= Seq(
        "dev.zio" %%% "zio-schema" % "1.7.5",
        "org.getshaka" %%% "native-converter" % "0.9.0",
        "dev.zio" %%% "zio-schema-derivation" % "1.7.5" % Test,
        "dev.zio" %%% "zio-test" % "2.1.23" % Test,
        "dev.zio" %%% "zio-test-sbt" % "2.1.23" % Test,
      ),
      testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
      Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)
        .withOutputPatterns(OutputPatterns.fromJSFile("%s.cjs"))),
    )

lazy val zioH3SchemaExt: Project = (project in file("scala-modules/exts/zio-h3-schema"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "zio-h3-schema-ext",

    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio-schema-derivation" % "1.7.5" % Test,
      "dev.zio" %%% "zio-test" % "2.1.23" % Test,
      "dev.zio" %%% "zio-test-sbt" % "2.1.23" % Test,
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)
      .withOutputPatterns(OutputPatterns.fromJSFile("%s.cjs"))),
    Test / jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(
      org.scalajs.jsenv.nodejs.NodeJSEnv.Config().withEnv(Map(
        "NODE_PATH" -> ((h3Ext / Compile / npmUpdate).value / "node_modules").getAbsolutePath,
      )),
    ),
  )
  .dependsOn(zioH3Ext, nativeConverterZIOSchemaExt)

lazy val zioMigrationsExt: Project = (project in file("scala-modules/exts/zio-migrations"))
  .configure(configureCommonJsProject)
  .settings(
    name    := "zio-migrations-ext",
    // The recovered migration experiment is not a supported release module.
    publish / skip := true,

  )

lazy val nuxtsvr: Project = (project in file("scala-modules/svr"))
  .configure(configureCommonJsProject)
  .settings(
    name                                                := "nuxtsvr",

    publish / skip                                      := true,
    scalaJSUseMainModuleInitializer                     := false,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)
      .withOutputPatterns(OutputPatterns.fromJSFile("%s.js"))
      .withESFeatures(_.withESVersion(ESVersion.ES2021))),
    Compile / fastLinkJS / scalaJSLinkerConfig ~= (_.withSourceMap(true)),
    Compile / fastLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / ".." / ".." / "server" / ".scala",
    Compile / fullLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / ".." / ".." / "server" / ".scala",
    cleanFiles += (Compile / fullLinkJS / scalaJSLinkerOutputDirectory).value,
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)
      .withOutputPatterns(OutputPatterns.fromJSFile("%s.cjs"))),
    Test / jsEnv := new org.scalajs.jsenv.nodejs.NodeJSEnv(
      org.scalajs.jsenv.nodejs.NodeJSEnv.Config().withEnv(Map(
        "NODE_PATH" -> ((h3Ext / Compile / npmUpdate).value / "node_modules").getAbsolutePath,
      )),
    ),
  )
  .dependsOn(exts)
