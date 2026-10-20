import org.scalajs.linker.interface.{ESVersion, ModuleKind, OutputPatterns}

// One toolchain for every module: Scala 3.9.0 with JDK 25 at build time.
// Kyo is the core runtime; the ZIO modules adapt it and inherit Kyo transitively.
lazy val kyoVersion             = "1.0.0-RC7"
lazy val zioVersion             = "2.1.26" // kyo-zio 1.0.0-RC7 pins this version
lazy val zioJsonVersion         = "0.7.44"
lazy val zioPreludeVersion      = "1.0.0-RC41"
lazy val zioSchemaVersion       = "1.7.5"
lazy val nativeConverterVersion = "0.9.0"
lazy val munitVersion           = "1.2.1"

// claude 2026-10-20: changed: zio-migrations is an example (#24)
// Explicitly exclude aggregate projects, applications and examples.
lazy val releaseModules = Seq(
  "h3Ext", "kyoH3Ext", "zioH3Ext", "nativeConverterZIOExt",
  "nativeConverterZIOJsonExt", "nativeConverterZIOSchemaExt", "nativeConverterKyoSchemaExt",
  "zioNuxtHubExt", "kyoNuxtHubExt",
)
addCommandAlias("releaseCheck", releaseModules.map(_ + "/makePom").mkString(";"))
addCommandAlias("releasePublishLocal", releaseModules.map(_ + "/publishLocal").mkString(";"))
addCommandAlias("releasePublish", releaseModules.map(_ + "/publish").mkString(";"))

inThisBuild(
  Seq(
    scalaVersion      := "3.9.0",
    organization      := "dev.yuri4n",
    homepage          := Some(url("https://github.com/yuri4n/h34s")),
    licenses          := List(License.Apache2), // claude 2026-10-19: created: the POM names the license
    scmInfo           := Some(ScmInfo(url("https://github.com/yuri4n/h34s"), "scm:git:https://github.com/yuri4n/h34s.git")),
    publishMavenStyle := true,
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
        "dev.zio"       %%% "zio-json"              % zioJsonVersion,
        "dev.zio"       %%% "zio-schema-derivation" % zioSchemaVersion,
        "dev.zio"       %%% "zio-schema-json"       % zioSchemaVersion,
        "dev.zio"       %%% "zio-schema"            % zioSchemaVersion,
        "dev.zio"       %%% "zio-streams"           % zioVersion,
        "dev.zio"       %%% "zio"                   % zioVersion,
        "eu.timepit"    %%% "refined"               % "0.11.3",
        "is.cir"        %%% "ciris-refined"         % "3.12.0",
        "is.cir"        %%% "ciris"                 % "3.12.0",
        "org.getshaka"  %%% "native-converter"      % nativeConverterVersion,
        "org.tpolecat"  %%% "skunk-core"            % "1.0.0-M12",
        "org.tpolecat"  %%% "skunk-refined"         % "1.0.0-M12",
        "org.typelevel" %%% "cats-effect"           % "3.6.3",
        "co.fs2"        %%% "fs2-core"              % "3.12.2",
        "dev.zio"       %%% "zio-test"              % zioVersion % Test,
        "dev.zio"       %%% "zio-test-sbt"          % zioVersion % Test,
      ),
    )

/** Scala.js tests link as CommonJS so Node can require the pinned npm test fixtures. */
lazy val commonJsTests = Seq(
  Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)
    .withOutputPatterns(OutputPatterns.fromJSFile("%s.cjs"))),
)

/** Node resolves npm fixtures from these test-js directories (override with -Dh34s.<name>.nodePath). */
def testJsEnv(fixtures: (String, File)*) =
  new org.scalajs.jsenv.nodejs.NodeJSEnv(
    org.scalajs.jsenv.nodejs.NodeJSEnv.Config().withEnv(Map(
      "NODE_PATH" -> fixtures.map { case (name, moduleDirectory) =>
        sys.props.getOrElse(s"h34s.$name.nodePath", (moduleDirectory / "test-js" / "node_modules").getAbsolutePath)
      }.mkString(java.io.File.pathSeparator),
    )),
  )

lazy val root = (project in file("."))
  .aggregate(exts, nuxtsvr, kyoExample, zioMigrationsExt) // claude 2026-10-20: changed: zio-migrations is an example (#24)
  .settings(
    publish / skip := true,
  )

// claude 2026-10-20: changed: the library aggregate keeps its id; its base is scala-modules (#23)
lazy val exts = (project in file("scala-modules"))
  .settings(
    name    := "exts",
    publish / skip := true,
  )
  .dependsOn(
    zioH3Ext,
    nativeConverterZIOExt,
    nativeConverterZIOJsonExt,
    nativeConverterZIOSchemaExt,
  )
  .aggregate(
    h3Ext,
    kyoH3Ext,
    nativeConverterKyoSchemaExt,
    kyoNuxtHubExt,
    zioH3Ext,
    zioNuxtHubExt,
    nativeConverterZIOExt,
    nativeConverterZIOJsonExt,
    nativeConverterZIOSchemaExt,
  )

// Effect-free native layer: hand-written internal h3/Node/Nitro facades and the
// shared public values (EventHandler, Request, SafeError, Secret, ...).
lazy val h3Ext: Project = (project in file("scala-modules/h3")) // claude 2026-10-20: changed: the flattened path (#23)
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "h3-ext",
    libraryDependencies ++= Seq(
      "org.getshaka"  %%% "native-converter" % nativeConverterVersion,
      "org.scalameta" %%% "munit"            % munitVersion % Test,
    ),
    commonJsTests,
    Test / jsEnv := testJsEnv("h3" -> baseDirectory.value),
  )

// The core: Kyo request lifecycle, body limits, errors, configuration, shared
// resources and streaming. Kyo consumers never acquire ZIO.
lazy val kyoH3Ext: Project = (project in file("scala-modules/kyo-h3")) // claude 2026-10-20: changed: the flattened path (#23)
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "kyo-h3-ext",
    libraryDependencies ++= Seq(
      "io.getkyo"     %%% "kyo-core"         % kyoVersion,
      "org.getshaka"  %%% "native-converter" % nativeConverterVersion,
      "org.scalameta" %%% "munit"            % munitVersion % Test,
    ),
    commonJsTests,
    Test / jsEnv := testJsEnv("h3" -> (h3Ext / baseDirectory).value),
  )
  // Tests reuse the native h3 event and Node response fixtures of h3-ext.
  .dependsOn(h3Ext % "compile->compile;test->test")

// Optional Kyo Schema bridge: `derives Schema, NativeSchema`. It needs kyo-schema only; JSON and
// YAML come from the application's own kyo-schema-json / kyo-schema-yaml dependency.
lazy val nativeConverterKyoSchemaExt: Project =
  (project in file("scala-modules/native-converter-kyo-schema")) // claude 2026-10-20: changed: the flattened path (#23)
    .enablePlugins(ScalaJSPlugin)
    .settings(
      name := "native-converter-kyo-schema-ext",
      libraryDependencies ++= Seq(
        "io.getkyo"     %%% "kyo-schema"       % kyoVersion,
        "org.getshaka"  %%% "native-converter" % nativeConverterVersion,
        // The tests compare the native shape with kyo-schema-json and round-trip YAML.
        "io.getkyo"     %%% "kyo-schema-json"  % kyoVersion   % Test,
        "io.getkyo"     %%% "kyo-schema-yaml"  % kyoVersion   % Test,
        "org.scalameta" %%% "munit"            % munitVersion % Test,
      ),
      commonJsTests,
      Test / jsEnv := testJsEnv("h3" -> (h3Ext / baseDirectory).value),
    )
    // InvalidInput comes from h3-ext. The h3 integration test reuses kyo-h3's test fixtures only.
    .dependsOn(h3Ext, kyoH3Ext % "test->test")

lazy val kyoExample: Project = (project in file("examples/nuxt/scala/kyo"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "kyo-example",
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
    // claude 2026-10-19: created: test-only kyo-sql for the opt-in PostgreSQL suite of the database
    // guide's code. It runs only when H34S_POSTGRES_URL is set; the linked application is unchanged.
    libraryDependencies ++= Seq(
      "io.getkyo"     %%% "kyo-sql-postgres" % kyoVersion   % Test,
      "org.scalameta" %%% "munit"            % munitVersion % Test,
    ),
    commonJsTests,
  )
  // One schema per model: `derives Schema, NativeSchema`.
  .dependsOn(kyoH3Ext, nativeConverterKyoSchemaExt)

// Kyo storage over Nitro's unstorage mounts (NuxtHub KV and cache are mounts).
lazy val kyoNuxtHubExt: Project = (project in file("scala-modules/kyo-nuxthub")) // claude 2026-10-20: changed: the flattened path (#23)
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "kyo-nuxthub-ext",
    libraryDependencies ++= Seq(
      "org.getshaka"  %%% "native-converter" % nativeConverterVersion,
      "org.scalameta" %%% "munit"            % munitVersion % Test,
    ),
    commonJsTests,
    Test / jsEnv := testJsEnv(
      "nuxthub" -> baseDirectory.value,
      "h3"      -> (h3Ext / baseDirectory).value,
    ),
  )
  // Tests install a fake h34s Nitro startup record with kyo-h3's NitroGlobal fixture.
  .dependsOn(kyoH3Ext % "compile->compile;test->test")

// The ZIO adapter over the Kyo core: ZHandler, ZStream bodies, ZShared and the awaiting
// ZIO <-> Kyo bridge. ZIO consumers get Kyo transitively.
lazy val zioH3Ext: Project = (project in file("scala-modules/zio-h3")) // claude 2026-10-20: changed: the flattened path (#23)
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "zio-h3-ext",
    libraryDependencies ++= Seq(
      "io.getkyo"    %%% "kyo-zio"               % kyoVersion,
      "dev.zio"      %%% "zio"                   % zioVersion,
      "dev.zio"      %%% "zio-prelude"           % zioPreludeVersion,
      "dev.zio"      %%% "zio-streams"           % zioVersion,
      "dev.zio"      %%% "zio-json"              % zioJsonVersion,
      "org.getshaka" %%% "native-converter"      % nativeConverterVersion,
      "dev.zio"      %%% "zio-schema-derivation" % zioSchemaVersion % Test,
      "dev.zio"      %%% "zio-test"              % zioVersion       % Test,
      "dev.zio"      %%% "zio-test-sbt"          % zioVersion       % Test,
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    commonJsTests,
    Test / jsEnv := testJsEnv("h3" -> (h3Ext / baseDirectory).value),
  )
  // Tests reuse the h3 event fixtures and NitroGlobal; schema request tests use NativeSchema.
  .dependsOn(
    kyoH3Ext % "compile->compile;test->test",
    nativeConverterZIOExt,
    nativeConverterZIOSchemaExt % "test->compile",
  )

lazy val zioNuxtHubExt: Project = (project in file("scala-modules/zio-nuxthub")) // claude 2026-10-20: changed: the flattened path (#23)
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "zio-nuxthub-ext",
    libraryDependencies ++= Seq(
      "io.getkyo"    %%% "kyo-zio"          % kyoVersion,
      "dev.zio"      %%% "zio"              % zioVersion,
      "org.getshaka" %%% "native-converter" % nativeConverterVersion,
      "dev.zio"      %%% "zio-test"         % zioVersion % Test,
      "dev.zio"      %%% "zio-test-sbt"     % zioVersion % Test,
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
    commonJsTests,
    Test / jsEnv := testJsEnv(
      "nuxthub" -> (kyoNuxtHubExt / baseDirectory).value,
      "h3"      -> (h3Ext / baseDirectory).value,
    ),
  )
  .dependsOn(kyoNuxtHubExt % "compile->compile;test->test", zioH3Ext)

lazy val nativeConverterZIOExt = (project in file("scala-modules/native-converter-zio")) // claude 2026-10-20: changed: the flattened path (#23)
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "native-converter-zio-ext",
    libraryDependencies ++= Seq(
      "dev.zio"      %%% "zio"              % zioVersion,
      "dev.zio"      %%% "zio-prelude"      % zioPreludeVersion,
      "dev.zio"      %%% "zio-streams"      % zioVersion,
      "dev.zio"      %%% "zio-json"         % zioJsonVersion,
      "org.getshaka" %%% "native-converter" % nativeConverterVersion,
    ),
  )
  // NativeConverter[Unit] lives in h3-ext; this module re-exports it.
  .dependsOn(h3Ext)

lazy val nativeConverterZIOJsonExt: Project =
  (project in file("scala-modules/native-converter-zio-json")) // claude 2026-10-20: changed: the flattened path (#23)
    .enablePlugins(ScalaJSPlugin)
    .settings(
      name := "native-converter-zio-json-ext",
      libraryDependencies ++= Seq(
        "dev.zio"      %%% "zio-json"         % zioJsonVersion,
        "org.getshaka" %%% "native-converter" % nativeConverterVersion,
      ),
    )
    .dependsOn(nativeConverterZIOExt)

// Optional ZIO Schema integration (`derives Schema, NativeSchema`). NativeConverter-only users
// do not need it. It depends on h3-ext for InvalidInput/ErrorEntry (effect-free).
lazy val nativeConverterZIOSchemaExt: Project =
  (project in file("scala-modules/native-converter-zio-schema")) // claude 2026-10-20: changed: the flattened path (#23)
    .enablePlugins(ScalaJSPlugin)
    .settings(
      name := "native-converter-zio-schema-ext",
      libraryDependencies ++= Seq(
        "dev.zio"      %%% "zio-schema"            % zioSchemaVersion,
        "org.getshaka" %%% "native-converter"      % nativeConverterVersion,
        "dev.zio"      %%% "zio-schema-derivation" % zioSchemaVersion % Test,
        "dev.zio"      %%% "zio-test"              % zioVersion       % Test,
        "dev.zio"      %%% "zio-test-sbt"          % zioVersion       % Test,
      ),
      testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework"),
      commonJsTests,
    )
    .dependsOn(h3Ext)

// claude 2026-10-20: changed: an example under examples/, outside the library aggregate (#24)
lazy val zioMigrationsExt: Project = (project in file("examples/zio-migrations"))
  .configure(configureCommonJsProject)
  .settings(
    name    := "zio-migrations-ext",
    // An unpublished example: ZIO Schema to Skunk codecs and PostgreSQL DDL. Not a release module.
    publish / skip := true,
  )

lazy val nuxtsvr: Project = (project in file("examples/nuxt/scala/zio"))
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
    commonJsTests,
    Test / jsEnv := testJsEnv("h3" -> (h3Ext / baseDirectory).value),
  )
  .dependsOn(zioH3Ext, nativeConverterZIOSchemaExt)
