val scala3Version = "3.8.2"

lazy val root = project
  .in(file("."))
  .settings(
    name         := "SeArChess",
    version      := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,

    libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test,

    // scoverage settings
    coverageEnabled          := false,   // enabled per-command, not by default
    coverageFailOnMinimum    := true,
    coverageMinimumStmtTotal := 90,
    coverageHighlighting     := true,
    coverageExcludedFiles    := ".*Main.*"  // exclude sbt scaffold
  )
