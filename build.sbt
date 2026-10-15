import org.scalajs.linker.interface.{ESVersion, ModuleKind, OutputPatterns}

inThisBuild(
  Seq(
    // scalaVersion      := "3.7.4",
    scalaVersion      := "3.5.2",
    organization      := "dev.yuri4n",
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
      libraryDependencies := Seq(
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
        "dev.zio"       %%% "zio-test"              % "2.1.24" % Test,
        "dev.zio"       %%% "zio-test-sbt"          % "2.1.24" % Test,
      ),
    )

lazy val root = (project in file("."))
  .aggregate(exts, nuxtsvr)
  .settings(
    publish / skip := true,
  )

lazy val exts = (project in file("scala-modules/exts"))
  .settings(
    name    := "exts",
    version := "0.1.0",
  )
  .dependsOn(
    // h3Ext,
    zioH3Ext,
    zioJsExt,
    zioMigrationsExt,
    nativeConverterZIOExt,
    nativeConverterZIOJsonExt,
  )
  .aggregate(
    h3Ext,
    zioH3Ext,
    zioJsExt,
    zioMigrationsExt,
    nativeConverterZIOExt,
    nativeConverterZIOJsonExt,
  )

lazy val h3Ext: Project = (project in file("scala-modules/exts/h3"))
  .settings(
    name    := "h3-ext",
    version := "0.1.0",
  )
  .enablePlugins(ScalablyTypedConverterPlugin)
  .settings(
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
    version := "0.1.0",
    libraryDependencies ++= Seq(
      "dev.zio"      %%% "zio"              % "2.1.23",
      "dev.zio"      %%% "zio-streams"      % "2.1.23",
      "dev.zio"      %%% "zio-schema"       % "1.7.5",
      "dev.zio"      %%% "zio-schema-json"  % "1.7.5",
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
        "NODE_PATH" -> ((h3Ext / crossTarget).value / "scalajs-bundler" / "main" / "node_modules").getAbsolutePath,
      )),
    ),
  )
  .dependsOn(h3Ext, zioJsExt, nativeConverterZIOExt)

lazy val zioJsExt: Project = (project in file("scala-modules/exts/zio-js"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name    := "zio-js-ext",
    version := "0.1.0",
    libraryDependencies ++= Seq(
      "dev.zio"      %%% "zio"              % "2.1.23",
      "dev.zio"      %%% "zio-streams"      % "2.1.23",
      "dev.zio"      %%% "zio-schema"       % "1.7.5",
      "dev.zio"      %%% "zio-schema-json"  % "1.7.5",
      "dev.zio"      %%% "zio-json"         % "0.7.38",
      "org.getshaka" %%% "native-converter" % "0.9.0",
    ),
  )

lazy val nativeConverterZIOExt = (project in file("scala-modules/exts/native-converter-zio"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name    := "native-converter-zio-ext",
    version := "0.1.0",
    libraryDependencies ++= Seq(
      "dev.zio"      %%% "zio"              % "2.1.23",
      "dev.zio"      %%% "zio-streams"      % "2.1.23",
      "dev.zio"      %%% "zio-schema"       % "1.7.5",
      "dev.zio"      %%% "zio-schema-json"  % "1.7.5",
      "dev.zio"      %%% "zio-json"         % "0.7.38",
      "org.getshaka" %%% "native-converter" % "0.9.0",
    ),
  )

lazy val nativeConverterZIOJsonExt: Project =
  (project in file("scala-modules/exts/native-converter-zio-json"))
    .enablePlugins(ScalaJSPlugin)
    .settings(
      name    := "native-converter-zio-json-ext",
      version := "0.1.0",
      libraryDependencies ++= Seq(
        "dev.zio"      %%% "zio-json"         % "0.7.38",
        "org.getshaka" %%% "native-converter" % "0.9.0",
      ),
    )
    .dependsOn(nativeConverterZIOExt)

lazy val zioMigrationsExt: Project = (project in file("scala-modules/exts/zio-migrations"))
  .configure(configureCommonJsProject)
  .settings(
    name    := "zio-migrations-ext",
    version := "0.1.0",
  )

lazy val nuxtsvr: Project = (project in file("scala-modules/svr"))
  .configure(configureCommonJsProject)
  .settings(
    name                                                := "nuxtsvr",
    version                                             := "0.1.0-SNAPSHOT",
    publish / skip                                      := true,
    scalaJSUseMainModuleInitializer                     := false,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)
      .withOutputPatterns(OutputPatterns.fromJSFile("%s.js"))
      .withESFeatures(_.withESVersion(ESVersion.ES2021))),
    Compile / fastLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / ".." / ".." / "server" / ".scala",
  )
  .dependsOn(exts)

// lazy val nuxtScala = project
//   .in(file("scala-modules/server-scala"))
//   .enablePlugins(ScalablyTypedConverterPlugin)
//   .settings(
//     name                                                := "nuxt-scala",
//     version                                             := "0.1.0",
//     scalacOptions ++= Seq(
//       "-Wunused:all",
//       "-Xkind-projector:underscores",
//     ),
//     libraryDependencies ++= Seq(
//       "dev.zio"      %%% "zio"              % "2.1.23",
//       "dev.zio"      %%% "zio-streams"      % "2.1.23",
//       "dev.zio"      %%% "zio-schema"       % "1.7.5",
//       "dev.zio"      %%% "zio-schema-json"  % "1.7.5",
//       "dev.zio"      %%% "zio-json"         % "0.7.38",
//       "org.getshaka" %%% "native-converter" % "0.9.0",
//     ),
//     scalaJSUseMainModuleInitializer                     := false,
//     scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)
//       .withOutputPatterns(OutputPatterns.fromJSFile("%s.cjs"))
//       .withESFeatures(_.withESVersion(ESVersion.ES2021))),
//     Compile / fastLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "../server/.scala",
//     Compile / fullLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "../server/.scala",
//     Compile / npmDependencies                           := Seq(
//       "h3" -> "1.15.4",
//       // "@types/node" -> "25.0.3",
//     ),
//     stIgnore += "@types/node",
//     // Run fastLinkJS whenever BSP incremental compile runs (bspBuildTargetCompile is an InputKey)
//     // Compile / bspBuildTargetCompile                     := (Compile / bspBuildTargetCompile)
//     //   .dependsOn(Compile / fastLinkJS)
//     //   .evaluated,
//     // Compile / compile := (Compile / compile).dependsOn(Compile / fullLinkJS).evaluated
//     // Compile / fullLinkJS := (Compile / fullLinkJS).triggeredBy(Compile / compile).value,
//   )
