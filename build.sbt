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
      // SCALA
      "org.scala-lang.modules" %% "scala-parallel-collections" % "1.1.0",

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
addCommandAlias(
  "cov",
  "set coverageEnabled := true; clean; coverage; test; coverageReport; coverageAggregate",
)

// fixme
addCommandAlias(
  "f",
  "reload; clean; update",
)

addCommandAlias(
  "c",
  "compile; test:compile",
)
addCommandAlias(
  "t",
  "test",
)

addCommandAlias(
  "tc",
  "test:compile",
)

addCommandAlias(
  "s",
  "scalafmt",
)

addCommandAlias(
  "r",
  "reload",
)

addCommandAlias(
  "s",
  "scalafix RemoveUnused;" +
    " scalafix OrganizeImports;" +
    " scalafix ProcedureSyntax;" +
    " scalafix NoValInForComprehension;" +
    " scalafix RedundantSyntax;" +
    " scalafix NoAutoTupling;" +
    " scalafix LeakingImplicitClassVal;",
)
