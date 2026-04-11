import org.scoverage.coveralls.CoverallsPlugin

val scala3Version  = "3.8.2"
val scalaFxVersion = "21.0.0-R32"
val javaFxVersion  = "21.0.1"

lazy val osClassifier: String = System.getProperty("os.name") match {
  case n if n.startsWith("Windows") => "win"
  case n if n.startsWith("Mac")     => "mac"
  case _                            => "linux"
}
lazy val javaFxModules = Seq("base", "controls", "graphics")

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

    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.19"      % Test,
      "org.scalafx"   %% "scalafx"   % scalaFxVersion,
      "com.lihaoyi"   %% "ujson"     % "4.0.2"
    ) ++ javaFxModules.map(m =>
      "org.openjfx" % s"javafx-$m" % javaFxVersion classifier osClassifier
    ),

    // Single entry point for `sbt run`; avoids the "multiple main classes" prompt.
    Compile / mainClass := Some("chess.Main"),
    run     / mainClass := Some("chess.Main"),

    // Fork run so JavaFX has a proper application thread.
    // NOTE: forked processes do not inherit sbt's stdin, so the TUI will
    // receive immediate EOF and exit.  The GUI is designed to survive this.
    run / fork := true,

    // scoverage settings
    coverageEnabled          := false,        // toggled per-command via report/ci aliases
    coverageFailOnMinimum    := true,
    coverageMinimumStmtTotal := 100,
    coverageHighlighting     := true,
    // Exclude JavaFX-dependent adapter code that cannot run without a display.
    // sbt-scoverage 2.0.11 + Scala 3: coverage uses the native Scala 3 compiler
    // flag (-coverage-out) but does NOT forward coverageExcludedFiles/Packages to
    // Scala 3's -coverage-exclude-files / -coverage-exclude-classlikes flags.
    // We therefore inject them manually via scalacOptions when coverage is active.
    Compile / compile / scalacOptions ++= {
      if (coverageEnabled.value)
        // -coverage-exclude-files takes a regex matched against the relative source
        // path from the project root.  Use . (any char) for path separators so the
        // pattern works on both Windows (\) and Unix (/).
        Seq(
          "-coverage-exclude-files:" +
          Seq(
            ".*adapter.gui.ChessApp.*",
            ".*adapter.gui.scene.*",
            ".*adapter.gui.render.*",
            ".*adapter.gui.animation.AnimationRunner.*",
            ".*adapter.gui.assets.SpriteSheetLoader.*",
            ".*adapter.gui.assets.PieceNodeFactory.*",
            ".*adapter.gui.assets.SpriteCatalogLoader.*",
            ".*adapter.textui.TuiRunner.*",
            ".*chess.Main.*",
            ".*adapter.textui.Console.*",
            ".*adapter.rest.RestServer.*",
            ".*adapter.rest.route.*"
          ).mkString("|")
        )
      else Seq.empty
    }
  )
