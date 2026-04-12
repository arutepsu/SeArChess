import org.scoverage.coveralls.CoverallsPlugin

// ── Versions ──────────────────────────────────────────────────────────────────

val scala3Version    = "3.8.2"
val scalaFxVersion   = "21.0.0-R32"
val javaFxVersion    = "21.0.1"
val http4sVersion    = "0.23.29"

lazy val osClassifier: String = System.getProperty("os.name") match {
  case n if n.startsWith("Windows") => "win"
  case n if n.startsWith("Mac")     => "mac"
  case _                            => "linux"
}
lazy val javaFxModules = Seq("base", "controls", "graphics")

// ── Shared settings ───────────────────────────────────────────────────────────

lazy val commonSettings = Seq(
  scalaVersion := scala3Version,
  libraryDependencies += "org.scalatest" %% "scalatest" % "3.2.19" % Test,
  // scoverage: coverage is toggled via the report/ci aliases on the root project;
  // individual modules inherit ThisBuild-level coverageEnabled.
  coverageFailOnMinimum := true,
  coverageMinimumStmtTotal := 100,
  coverageHighlighting := true
)

// Coverage exclusion helper: produces the -coverage-exclude-files scalac flag
// when coverage is enabled.  Pass a seq of regex fragments; they are OR-joined.
def excludeFromCoverage(patterns: String*) = Seq(
  Compile / compile / scalacOptions ++= {
    if (coverageEnabled.value)
      Seq("-coverage-exclude-files:" + patterns.mkString("|"))
    else Seq.empty
  }
)

// ── Module: domain ─────────────────────────────────────────────────────────────

lazy val domain = project
  .in(file("modules/domain"))
  .settings(commonSettings)

// ── Module: notation ──────────────────────────────────────────────────────────

lazy val notation = project
  .in(file("modules/notation"))
  .settings(
    commonSettings,
    libraryDependencies += "com.lihaoyi" %% "ujson" % "4.0.2"
  )
  .dependsOn(domain)

// ── Module: application ───────────────────────────────────────────────────────

lazy val application = project
  .in(file("modules/application"))
  .settings(commonSettings)
  .dependsOn(domain)

// ── Module: adapter-persistence ───────────────────────────────────────────────

lazy val adapterPersistence = project
  .in(file("modules/adapter-persistence"))
  .settings(commonSettings)
  .dependsOn(application)

// ── Module: adapter-ai ────────────────────────────────────────────────────────

lazy val adapterAi = project
  .in(file("modules/adapter-ai"))
  .settings(commonSettings)
  .dependsOn(application)

// ── Module: adapter-event ─────────────────────────────────────────────────────

lazy val adapterEvent = project
  .in(file("modules/adapter-event"))
  .settings(commonSettings)
  .dependsOn(application)

// ── Module: adapter-rest-shared (DTOs and mappers shared by all REST adapters) ─

lazy val adapterRestShared = project
  .in(file("modules/adapter-rest-shared"))
  .settings(
    commonSettings,
    libraryDependencies += "com.lihaoyi" %% "ujson" % "4.0.2"
  )
  .dependsOn(application)

// ── Module: adapter-rest-jdk (legacy spike) ───────────────────────────────────

lazy val adapterRestJdk = project
  .in(file("modules/adapter-rest-jdk"))
  .settings(
    commonSettings,
    // RestServer and route handlers require a live HTTP socket; exclude from coverage.
    // Note: unescaped . matches any char so it works as path separator on Windows (\) and Unix (/).
    excludeFromCoverage(
      ".*adapter.rest.RestServer.*",
      ".*adapter.rest.route.*"
    )
  )
  .dependsOn(adapterRestShared, adapterPersistence)

// ── Module: adapter-rest-http4s (authoritative REST adapter) ──────────────────

lazy val adapterRestHttp4s = project
  .in(file("modules/adapter-rest-http4s"))
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl"          % http4sVersion
    ),
    // Http4sServer itself (Ember binding) cannot be easily tested without a live socket.
    // Route classes (Http4sSessionRoutes, Http4sGameRoutes) are tested in-memory and
    // therefore ARE included in coverage.
    excludeFromCoverage(".*adapter.http4s.Http4sServer.*")
  )
  // adapterPersistence is only needed for test fixtures (InMemoryGameRepository etc.)
  .dependsOn(adapterRestShared, adapterPersistence % Test)

// ── Module: adapter-websocket ─────────────────────────────────────────────────

lazy val adapterWebsocket = project
  .in(file("modules/adapter-websocket"))
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      "com.lihaoyi"        %% "ujson"            % "4.0.2",
      "org.java-websocket"  % "Java-WebSocket"   % "1.5.7"
    ),
    excludeFromCoverage(
      ".*adapter.websocket.ChessWebSocketServer.*",
      ".*adapter.websocket.JavaWebSocketConnection.*"
    )
  )
  .dependsOn(application, adapterEvent)

// ── Module: adapter-gui ───────────────────────────────────────────────────────

lazy val adapterGui = project
  .in(file("modules/adapter-gui"))
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      "org.scalafx" %% "scalafx" % scalaFxVersion
    ) ++ javaFxModules.map(m =>
      "org.openjfx" % s"javafx-$m" % javaFxVersion classifier osClassifier
    ),
    libraryDependencies += "com.lihaoyi" %% "ujson" % "4.0.2",
    excludeFromCoverage(
      ".*adapter.gui.ChessApp.*",
      ".*adapter.gui.scene.*",
      ".*adapter.gui.render.*",
      ".*adapter.gui.animation.AnimationRunner.*",
      ".*adapter.gui.assets.SpriteSheetLoader.*",
      ".*adapter.gui.assets.PieceNodeFactory.*",
      ".*adapter.gui.assets.SpriteCatalogLoader.*"
    )
  )
  .dependsOn(application, notation, adapterPersistence, adapterAi, adapterEvent)

// ── Module: adapter-tui ───────────────────────────────────────────────────────

lazy val adapterTui = project
  .in(file("modules/adapter-tui"))
  .settings(
    commonSettings,
    excludeFromCoverage(
      ".*adapter.textui.TuiRunner.*",
      ".*adapter.textui.Console.*"
    )
  )
  .dependsOn(application, adapterPersistence)

// ── Module: main ──────────────────────────────────────────────────────────────

lazy val main = project
  .in(file("modules/main"))
  .settings(
    commonSettings,
    // Main only wires adapters; nothing to cover meaningfully.
    coverageMinimumStmtTotal := 0,
    Compile / mainClass := Some("chess.Main"),
    run     / mainClass := Some("chess.Main"),
    run     / fork      := true,
    excludeFromCoverage(".*chess.Main.*")
  )
  .dependsOn(adapterGui, adapterTui, adapterRestHttp4s, adapterRestJdk,
             adapterWebsocket, adapterAi, adapterEvent)

// ── Root aggregate ────────────────────────────────────────────────────────────

addCommandAlias("build",   "compile")
addCommandAlias("rebuild", ";clean;compile")
addCommandAlias("check",   ";compile;test")
addCommandAlias("report",
  ";set ThisBuild/coverageEnabled := true;clean;test;coverageReport;set ThisBuild/coverageEnabled := false")
addCommandAlias("ci",
  ";set ThisBuild/coverageEnabled := true;clean;test;coverageReport;coveralls;set ThisBuild/coverageEnabled := false")

lazy val root = project
  .in(file("."))
  .enablePlugins(CoverallsPlugin)
  .settings(
    name         := "SeArChess",
    version      := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    // Root project has no sources; all code lives in submodules.
    // Disable source scanning so the legacy src/ tree (kept as reference) is ignored.
    Compile / unmanagedSourceDirectories := Nil,
    Test    / unmanagedSourceDirectories := Nil,
    // Root project has no sources; coverage aggregation happens via subprojects.
    coverageEnabled := false
  )
  .aggregate(
    domain, notation, application,
    adapterPersistence, adapterAi, adapterEvent,
    adapterRestShared, adapterRestJdk, adapterRestHttp4s,
    adapterWebsocket, adapterGui, adapterTui,
    main
  )
