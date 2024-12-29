inThisBuild(
  List(
    scalaVersion      := "3.6.2",
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
  )
)

val zioVersion       = "2.1.9"
val zioHttpVersion   = "3.0.1"
val zioConfigVersion = "4.0.2"

scalacOptions ++= Seq(
  "-Wunused:all"
)

// Reduce the number of forked JVMs for tests
Test / fork := false

// Disable parallel execution if causing issues
Test / parallelExecution := false

lazy val root = (project in file("."))
  .settings(
    name := "BlogBlitz",
    libraryDependencies ++= Seq(
      // ZIO
      "dev.zio" %% "zio"         % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,

      // ZIO HTTP
      "dev.zio" %% "zio-http" % zioHttpVersion,

      // LOGGING
      "dev.zio"       %% "zio-logging"       % zioVersion,
      "dev.zio"       %% "zio-logging-slf4j" % zioVersion,
      "ch.qos.logback" % "logback-classic"   % "1.2.11",

      // CONFIGURATION
      "dev.zio" %% "zio-config"          % zioConfigVersion,
      "dev.zio" %% "zio-config-magnolia" % zioConfigVersion,
      "dev.zio" %% "zio-config-typesafe" % zioConfigVersion,
      "dev.zio" %% "zio-config-refined"  % zioConfigVersion,
      "dev.zio" %% "zio-config-yaml"     % zioConfigVersion,

      // TESTING
      "dev.zio" %% "zio-test"         % zioVersion     % Test,
      "dev.zio" %% "zio-test-sbt"     % zioVersion     % Test,
      "dev.zio" %% "zio-http-testkit" % zioHttpVersion % Test,
    ),
  )

// SBT convinience aliases
commands += Command.command("cls") { state =>
  print("\033c")
  state
}

def withCls(alias: String, command: String) =
  addCommandAlias(alias, s"cls; $command")

withCls(
  "cov",
  "set coverageEnabled := true; clean; coverage; test; coverageReport; coverageAggregate",
)

// fixme
withCls(
  "f",
  "reload; clean; update",
)

withCls(
  "c",
  "compile; test:compile",
)
withCls(
  "t",
  "test",
)

withCls(
  "tc",
  "test:compile",
)

withCls(
  "s",
  "scalafmt",
)

withCls(
  "r",
  "reload",
)

withCls(
  "x",
  "run",
)

withCls(
  "q",
  "exit",
)

withCls(
  "s",
  "scalafix RemoveUnused;" +
    " scalafix OrganizeImports;" +
    " scalafix ProcedureSyntax;" +
    " scalafix NoValInForComprehension;" +
    " scalafix RedundantSyntax;" +
    " scalafix NoAutoTupling;" +
    " scalafix LeakingImplicitClassVal;",
)
