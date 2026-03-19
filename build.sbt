import org.scoverage.coveralls.CoverallsPlugin

val scala3Version = "3.8.2"

addCommandAlias("build",    "compile")
addCommandAlias("rebuild",  ";clean;compile")
addCommandAlias("check",    ";compile;test")
addCommandAlias("report", ";set coverageEnabled := true;clean;test;coverageReport;set coverageEnabled := false")
addCommandAlias("ci",     ";set coverageEnabled := true;clean;test;coverageReport;coveralls;set coverageEnabled := false")

lazy val root = project
  .in(file("."))
  .enablePlugins(CoverallsPlugin)
  .settings(
    name         := "SeArChess",
    version      := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,

    libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test,

    // scoverage settings
    coverageEnabled          := false,        // toggled per-command via report/ci aliases
    coverageFailOnMinimum    := true,
    coverageMinimumStmtTotal := 80,           // 2.0.11 counts Main.scala; threshold reflects that
    coverageHighlighting     := true,
    coverageExcludedPackages := "<empty>.*"   // excludes the default sbt Main scaffold
  )
