import org.scalajs.linker.interface.{ESVersion, ModuleKind}

inThisBuild(
  Seq(
    // scalaVersion      := "3.7.4",
    scalaVersion      := "3.5.2",
    organization      := "dev.yuri4n",
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
  ),
)

lazy val root = (project in file("."))
  .aggregate(nuxtScala)
  .settings(
    publish / skip := true,
  )

lazy val nuxtScala = project
  .in(file("server-scala"))
  .enablePlugins(ScalablyTypedConverterPlugin)
  .settings(
    name                                                := "nuxt-scala",
    version                                             := "0.1.0",
    scalacOptions ++= Seq(
      "-Wunused:all",
    ),
    libraryDependencies ++= Seq(
      "dev.zio" %%% "zio"             % "2.1.23",
      "dev.zio" %%% "zio-streams"     % "2.1.23",
      "dev.zio" %%% "zio-schema"      % "1.7.5",
      "dev.zio" %%% "zio-schema-json" % "1.7.5",
      "dev.zio" %%% "zio-json"        % "0.7.38",
    ),
    scalaJSUseMainModuleInitializer                     := false,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)
      .withESFeatures(_.withESVersion(ESVersion.ES2021))),
    Compile / fastLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "../server/.scala",
    Compile / fullLinkJS / scalaJSLinkerOutputDirectory := baseDirectory.value / "../server/.scala",
    Compile / npmDependencies                           := Seq(
      "h3" -> "1.15.4",
      // "@types/node" -> "25.0.3",
    ),
    stIgnore += "@types/node",
    // Run fastLinkJS whenever BSP incremental compile runs (bspBuildTargetCompile is an InputKey)
    Compile / bspBuildTargetCompile                     := (Compile / bspBuildTargetCompile)
      .dependsOn(Compile / fastLinkJS)
      .evaluated,
    // Compile / compile := (Compile / compile).dependsOn(Compile / fullLinkJS).evaluated
    Compile / fullLinkJS                                := (Compile / fullLinkJS).triggeredBy(Compile / compile).value,
  )
