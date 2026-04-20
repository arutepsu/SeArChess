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
  libraryDependencies += "org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0",
  libraryDependencies += "com.lihaoyi" %% "fastparse" % "3.1.1",
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

// ── Module: history ───────────────────────────────────────────────────────────

lazy val history = project
  .in(file("modules/history"))
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "ujson"       % "4.0.2",
      "org.xerial"   % "sqlite-jdbc" % "3.46.1.3"
    )
  )
  // adapterPersistence and adapterEvent are only needed for test fixtures
  // (InMemoryGameRepository, CollectingEventPublisher, etc.)
  .dependsOn(application, notation, adapterPersistence % Test, adapterEvent % Test)

// ── Module: adapter-persistence ───────────────────────────────────────────────

lazy val adapterPersistence = project
  .in(file("modules/adapter-persistence"))
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "ujson"        % "4.0.2",
      "org.xerial"   % "sqlite-jdbc"  % "3.46.1.3"
    )
  )
  // adapterEvent is only needed for test fixtures (CollectingEventPublisher).
  .dependsOn(application, adapterEvent % Test)

// ── Module: adapter-ai ────────────────────────────────────────────────────────

lazy val adapterAi = project
  .in(file("modules/adapter-ai"))
  .settings(
    commonSettings,
    libraryDependencies += "com.lihaoyi" %% "ujson" % "4.0.2"
  )
  // adapterPersistence and adapterEvent are only needed for test fixtures
  // (InMemorySessionRepository, CollectingEventPublisher).
  .dependsOn(application, notation, adapterPersistence % Test, adapterEvent % Test)

// ── Module: adapter-event ─────────────────────────────────────────────────────

lazy val adapterEvent = project
  .in(file("modules/adapter-event"))
  .settings(
    commonSettings,
    libraryDependencies += "com.lihaoyi" %% "ujson" % "4.0.2"
  )
  .dependsOn(application)

// ── Module: adapter-rest-shared (DTOs and mappers shared by all REST adapters) ─

lazy val adapterRestContract = project
  .in(file("modules/adapter-rest-contract"))
  .settings(
    commonSettings,
    libraryDependencies += "com.lihaoyi" %% "ujson" % "4.0.2"
  )
  .dependsOn(application)


// ── Module: adapter-rest-http4s (authoritative REST adapter) ──────────────────

lazy val adapterRestHttp4s = project
  .in(file("modules/adapter-rest-http4s"))
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-dsl" % http4sVersion
    ),
    // Http4sApp itself (route composition wrapper) is excluded; route classes
    // (Http4sSessionRoutes, Http4sGameRoutes) are tested in-memory and therefore
    // ARE included in coverage.
    excludeFromCoverage(".*adapter.http4s.Http4sApp.*")
  )
  // adapterPersistence is only needed for test fixtures (InMemoryGameRepository etc.)
  .dependsOn(adapterRestContract, adapterPersistence % Test)

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
  .dependsOn(application, notation, adapterEvent, adapterPersistence)

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

// ── App: startup-shared ──────────────────────────────────────────────────────

lazy val startupShared = project
  .in(file("apps/startup-shared"))
  .settings(
    commonSettings,
    coverageMinimumStmtTotal := 0,
    excludeFromCoverage(
      ".*chess.startup.assembly.EventAssembly.*",
      ".*chess.startup.assembly.EventWiring.*",
      ".*chess.startup.assembly.PersistenceAssembly.*",
      ".*chess.startup.assembly.PersistenceWiring.*",
      ".*chess.startup.assembly.CoreAssembly.*",
      ".*chess.startup.assembly.AppContext.*",
      ".*chess.startup.assembly.ObservableGame.*",
      ".*chess.config.*"
    )
  )
  .dependsOn(
    application,
    adapterPersistence,
    adapterEvent,
    adapterWebsocket
  )

// ── App: desktop-gui ─────────────────────────────────────────────────────────

lazy val desktopGui = project
  .in(file("apps/desktop-gui"))
  .settings(
    commonSettings,
    coverageMinimumStmtTotal := 0,
    Compile / mainClass := Some("chess.guiapp.GuiMain"),
    run / mainClass     := Some("chess.guiapp.GuiMain"),
    run / fork          := true,
    excludeFromCoverage(
      ".*chess.guiapp.GuiMain.*",
      ".*chess.guiapp.GuiWiring.*"
    )
  )
  .dependsOn(startupShared, adapterGui)

// ── App: tui-cli ─────────────────────────────────────────────────────────────

lazy val tuiCli = project
  .in(file("apps/tui-cli"))
  .settings(
    commonSettings,
    coverageMinimumStmtTotal := 0,
    Compile / mainClass := Some("chess.tuiapp.TuiMain"),
    run / mainClass     := Some("chess.tuiapp.TuiMain"),
    run / fork          := true,
    excludeFromCoverage(
      ".*chess.tuiapp.TuiMain.*",
      ".*chess.tuiapp.TuiWiring.*"
    )
  )
  .dependsOn(startupShared, adapterTui)

// ── App: bootstrap-server ────────────────────────────────────────────────────

lazy val bootstrapServer = project
  .in(file("apps/bootstrap-server"))
  .enablePlugins(JavaAppPackaging)
  .settings(
    commonSettings,
    name := "searchess-game-service",
    coverageMinimumStmtTotal := 0,
    Compile / mainClass := Some("chess.server.ServerMain"),
    run / mainClass     := Some("chess.server.ServerMain"),
    run / fork          := true,
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion
    ),
    excludeFromCoverage(
      ".*chess.server.ServerMain.*",
      ".*chess.server.ServerWiring.*",
      ".*chess.server.ServerRuntime.*",
      ".*chess.server.http.HealthRoutes.*",
      ".*chess.server.http.CorsMiddleware.*"
    )
  )
  .dependsOn(
    startupShared,
    adapterRestHttp4s,
    adapterWebsocket,
    adapterAi,
    adapterEvent,
    adapterPersistence
  )

// ── App: history-service ─────────────────────────────────────────────────────

lazy val historyService = project
  .in(file("apps/history-service"))
  .enablePlugins(JavaAppPackaging)
  .settings(
    commonSettings,
    name := "searchess-history-service",
    coverageMinimumStmtTotal := 0,
    Compile / mainClass := Some("chess.historyservice.HistoryServiceMain"),
    run / mainClass     := Some("chess.historyservice.HistoryServiceMain"),
    run / fork          := true,
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl"          % http4sVersion
    ),
    excludeFromCoverage(
      ".*chess.historyservice.HistoryServiceMain.*",
      ".*chess.historyservice.HistoryServiceConfig.*",
      ".*chess.historyservice.HistoryRoutes.*"
    )
  )
  .dependsOn(history)

// ── Aliases ───────────────────────────────────────────────────────────────────
//
// Full-project workflow:
//   build    — compile everything
//   rebuild  — clean + compile everything
//   check    — compile + test everything
//   report   — clean + test + coverage report (local)
//   ci       — clean + test + coverage report + coveralls (CI)
//
// Per-module test aliases:
//   testDomain              testNotation            testApplication
//   testAdapterPersistence  testAdapterAi           testAdapterEvent
//   testAdapterRestContract testAdapterRestHttp4s
//   testAdapterWebsocket    testAdapterGui          testAdapterTui
//   testStartupShared       testBootstrapServer
//   testDesktopGui          testTuiCli
//
// Grouped aliases by architectural concern:
//   testCore         — domain + notation + application
//   testInfra        — adapter-persistence + adapter-event + adapter-ai + adapter-websocket
//   testRest         — adapter-rest-contract + adapter-rest-http4s
//   testUi           — adapter-gui + adapter-tui
//   testAllAdapters  — all adapter modules
//   testApps         — startup-shared + bootstrap-server + desktop-gui + tui-cli
//
// Compile slice aliases:
//   compileCore      — domain + notation + application
//   compileRest      — adapter-rest-contract + adapter-rest-http4s
//   compileUi        — adapter-gui + adapter-tui

// ── Full-project ──────────────────────────────────────────────────────────────

addCommandAlias("build",   "compile")
addCommandAlias("rebuild", ";clean;compile")
addCommandAlias("check",   ";compile;test")
addCommandAlias("report",
  ";set ThisBuild/coverageEnabled := true;clean;test;coverageAggregate;set ThisBuild/coverageEnabled := false")
addCommandAlias("ci",
  ";set ThisBuild/coverageEnabled := true;clean;test;coverageAggregate;coveralls;set ThisBuild/coverageEnabled := false")

// ── Per-module test ───────────────────────────────────────────────────────────

addCommandAlias("testDomain",             "domain/test")
addCommandAlias("testNotation",           "notation/test")
addCommandAlias("testApplication",        "application/test")
addCommandAlias("testHistory",            "history/test")
addCommandAlias("testAdapterPersistence", "adapterPersistence/test")
addCommandAlias("testAdapterAi",          "adapterAi/test")
addCommandAlias("testAdapterEvent",       "adapterEvent/test")
addCommandAlias("testAdapterRestContract","adapterRestContract/test")
addCommandAlias("testAdapterRestHttp4s",  "adapterRestHttp4s/test")
addCommandAlias("testAdapterWebsocket",   "adapterWebsocket/test")
addCommandAlias("testAdapterGui",         "adapterGui/test")
addCommandAlias("testAdapterTui",         "adapterTui/test")
addCommandAlias("testStartupShared",      "startupShared/test")
addCommandAlias("testBootstrapServer",    "bootstrapServer/test")
addCommandAlias("testHistoryService",     "historyService/test")
addCommandAlias("testDesktopGui",         "desktopGui/test")
addCommandAlias("testTuiCli",             "tuiCli/test")

// ── Grouped test: by architectural concern ────────────────────────────────────

addCommandAlias("testCore",
  ";domain/test;notation/test;application/test;history/test")

addCommandAlias("testInfra",
  ";adapterPersistence/test;adapterEvent/test;adapterAi/test;adapterWebsocket/test")

addCommandAlias("testRest",
  ";adapterRestContract/test;adapterRestHttp4s/test")

addCommandAlias("testUi",
  ";adapterGui/test;adapterTui/test")

addCommandAlias("testAllAdapters",
  ";adapterPersistence/test;adapterEvent/test;adapterAi/test;adapterWebsocket/test" +
  ";adapterRestContract/test;adapterRestHttp4s/test" +
  ";adapterGui/test;adapterTui/test")

addCommandAlias("testApps",
  ";startupShared/test;bootstrapServer/test;historyService/test;desktopGui/test;tuiCli/test")

// ── Compile slices ────────────────────────────────────────────────────────────

addCommandAlias("compileCore",
  ";domain/compile;notation/compile;application/compile")

addCommandAlias("compileRest",
  ";adapterRestContract/compile;adapterRestHttp4s/compile")

addCommandAlias("compileUi",
  ";adapterGui/compile;adapterTui/compile")

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
    domain, notation, application, history,
    adapterPersistence, adapterAi, adapterEvent,
    adapterRestContract, adapterRestHttp4s,
    adapterWebsocket, adapterGui, adapterTui,
    startupShared, bootstrapServer, historyService, desktopGui, tuiCli
  )
