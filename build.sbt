import org.scoverage.coveralls.CoverallsPlugin
import wartremover.WartRemover.autoImport._

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
  coverageMinimumStmtTotal := 80,
  coverageHighlighting := true,
  // WartRemover: errors on rules safe for this codebase today.
  // Null/Return/OptionPartial have zero legitimate uses in functional Scala 3.
  wartremoverErrors ++= Seq(
    Wart.OptionPartial
  ),

  // Warnings for rules that fire on intentional patterns (see notes below):
  //   Throw       – domain uses throw AssertionError as unreachable-branch guards
  //   Var         – adapters and concurrency code use var legitimately
  //   AsInstanceOf / IsInstanceOf – worth surfacing but not blocking
  wartremoverWarnings ++= Seq(
    Wart.Null,
    Wart.Return,
    Wart.Throw,
    Wart.Var,
    Wart.AsInstanceOf,
    Wart.IsInstanceOf
  )
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

// Module: domain

lazy val domain = project
  .in(file("modules/domain"))
  .settings(commonSettings)

// Module: notation

lazy val notation = project
  .in(file("modules/notation"))
  .settings(
    commonSettings,
    libraryDependencies += "com.lihaoyi" %% "ujson" % "4.0.2"
  )
  .dependsOn(domain)

// Module: observability

lazy val observability = project
  .in(file("modules/observability"))
  .settings(commonSettings)

// Module: game-contract

lazy val gameContract = project
  .in(file("modules/game-contract"))
  .settings(commonSettings)
  .dependsOn(domain)

// Module: game-core

lazy val gameCore = project
  .in(file("apps/game-service/modules/core"))
  .settings(commonSettings)
  .dependsOn(domain, gameContract)

// Module: ai-contract (neutral internal Game <-> AI wire contract)

lazy val aiContract = project
  .in(file("modules/ai-contract"))
  .settings(
    commonSettings,
    libraryDependencies += "com.lihaoyi" %% "ujson" % "4.0.2"
  )

// Module: history

lazy val history = project
  .in(file("apps/history-service/modules/core"))
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "ujson"       % "4.0.2",
      "org.xerial"   % "sqlite-jdbc" % "3.46.1.3"
    )
  )
  // adapterPersistence and adapterEvent are only needed for legacy test fixtures
  // (InMemoryGameRepository, CollectingEventPublisher, etc.).
  .dependsOn(gameContract, notation, observability, adapterPersistence % Test, adapterEvent % Test)

// Module: adapter-persistence

lazy val adapterPersistence = project
  .in(file("apps/game-service/modules/persistence"))
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "ujson"        % "4.0.2",
      "org.xerial"   % "sqlite-jdbc"  % "3.46.1.3",
      "com.typesafe.slick" %% "slick" % "3.6.1",
      "org.postgresql" % "postgresql" % "42.7.7",
      "org.mongodb" % "mongodb-driver-sync" % "5.2.1"
    )
  )
  // Event modules are only needed for test fixtures and transactional outbox specs.
  .dependsOn(
    gameCore,
    migration % "compile->compile;test->test",
    adapterEvent % Test,
    gameEventContract % Test,
    gameHistoryDelivery % Test
  )

// Module: migration

lazy val migration = project
  .in(file("apps/game-service/modules/migration"))
  .settings(commonSettings)
  .dependsOn(gameCore)

// Module: adapter-ai

lazy val adapterAi = project
  .in(file("apps/game-service/modules/ai"))
  .settings(
    commonSettings,
    libraryDependencies += "com.lihaoyi" %% "ujson" % "4.0.2"
  )
  // adapterPersistence and adapterEvent are only needed for test fixtures
  // (InMemorySessionRepository, CollectingEventPublisher).
  .dependsOn(gameCore, notation, aiContract, observability, adapterPersistence % Test, adapterEvent % Test)

// Module: adapter-event (internal in-process publishers/test collectors)

lazy val adapterEvent = project
  .in(file("apps/game-service/modules/eventing"))
  .settings(commonSettings)
  .dependsOn(gameContract)

// Module: game-event-contract (Game event JSON wire serializer)

lazy val gameEventContract = project
  .in(file("modules/game-event-contract"))
  .settings(
    commonSettings,
    libraryDependencies += "com.lihaoyi" %% "ujson" % "4.0.2"
  )
  .dependsOn(gameContract)

// Module: game-history-delivery (Game-owned History outbox/forwarder)

lazy val gameHistoryDelivery = project
  .in(file("apps/game-service/modules/history-delivery"))
  .settings(
    commonSettings,
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "ujson"       % "4.0.2",
      "org.xerial"   % "sqlite-jdbc" % "3.46.1.3"
    )
  )
  .dependsOn(gameContract, gameEventContract, observability)

// Module: adapter-rest-contract (wire DTOs/codecs only)

lazy val adapterRestContract = project
  .in(file("modules/adapter-rest-contract"))
  .settings(
    commonSettings,
    libraryDependencies += "com.lihaoyi" %% "ujson" % "4.0.2"
  )


// Module: adapter-rest-http4s (authoritative REST adapter)

lazy val adapterRestHttp4s = project
  .in(file("apps/game-service/modules/rest-http4s"))
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
  // gameCore is required by the HTTP adapter's internal mapping layer; the
  // public adapter-rest-contract module remains wire-only.
  // adapterPersistence is only needed for test fixtures (InMemoryGameRepository etc.)
  .dependsOn(adapterRestContract, gameCore, notation, adapterPersistence % Test)

// Module: adapter-websocket

lazy val adapterWebsocket = project
  .in(file("apps/game-service/modules/websocket"))
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
  .dependsOn(gameCore)

// Module: adapter-gui

lazy val adapterGui = project
  .in(file("apps/desktop-gui/modules/gui"))
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
  .dependsOn(gameCore, notation, adapterPersistence, adapterEvent % Test)

// Module: adapter-tui

lazy val adapterTui = project
  .in(file("apps/tui-cli/modules/tui"))
  .settings(
    commonSettings,
    excludeFromCoverage(
      ".*adapter.textui.TuiRunner.*",
      ".*adapter.textui.Console.*"
    )
  )
  .dependsOn(gameCore, adapterPersistence)

// ── App: startup-shared ──────────────────────────────────────────────────────

lazy val startupShared = project
  .in(file("apps/startup-shared"))
  .settings(
    commonSettings,
    coverageMinimumStmtTotal := 0,
    excludeFromCoverage(
      ".*chess.startup.local.LocalPersistenceAssembly.*",
      ".*chess.startup.local.LocalPersistenceWiring.*",
      ".*chess.startup.local.LocalGameAssembly.*",
      ".*chess.startup.local.LocalAppContext.*",
      ".*chess.startup.local.ObservableGame.*",
      ".*chess.startup.local.LocalRuntimeConfig.*",
      ".*chess.startup.local.LocalRuntimeConfigLoader.*"
    )
  )
  .dependsOn(
    gameCore,
    adapterPersistence
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

// ── App: game-service ────────────────────────────────────────────────────────

lazy val gameService = project
  .in(file("apps/game-service"))
  .enablePlugins(JavaAppPackaging)
  .settings(
    commonSettings,
    name := "searchess-game-service",
    coverageMinimumStmtTotal := 0,
    Compile / mainClass := Some("chess.server.GameServiceMain"),
    run / mainClass     := Some("chess.server.GameServiceMain"),
    run / fork          := true,
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion
    ),
    excludeFromCoverage(
      ".*chess.server.ServerMain.*",
      ".*chess.server.GameServiceMain.*",
      ".*chess.server.ServerWiring.*",
      ".*chess.server.GameServiceComposition.*",
      ".*chess.server.ServerRuntime.*",
      ".*chess.server.assembly.EventAssembly.*",
      ".*chess.server.assembly.EventWiring.*",
      ".*chess.server.assembly.CoreAssembly.*",
      ".*chess.server.assembly.AppContext.*",
      ".*chess.server.assembly.CoreEventBindings.*",
      ".*chess.server.assembly.PersistenceAssembly.*",
      ".*chess.server.assembly.PersistenceWiring.*",
      ".*chess.server.config.*",
      ".*chess.server.http.HealthRoutes.*",
      ".*chess.server.http.CorsMiddleware.*"
    )
  )
  .dependsOn(
    adapterRestHttp4s,
    adapterWebsocket,
    adapterAi,
    adapterEvent,
    gameEventContract,
    gameHistoryDelivery,
    migration,
    adapterPersistence,
    observability
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
  .dependsOn(history, gameEventContract, observability, gameHistoryDelivery % Test)

// App: ai-service

lazy val aiService = project
  .in(file("apps/ai-service"))
  .enablePlugins(JavaAppPackaging)
  .settings(
    commonSettings,
    name := "searchess-ai-service",
    coverageMinimumStmtTotal := 0,
    Compile / mainClass := Some("chess.aiservice.AiServiceMain"),
    run / mainClass     := Some("chess.aiservice.AiServiceMain"),
    run / fork          := true,
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl"          % http4sVersion,
      "com.lihaoyi" %% "ujson"              % "4.0.2"
    ),
    excludeFromCoverage(
      ".*chess.aiservice.*"
    )
  )
  .dependsOn(aiContract, observability)

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
//   testDomain              testNotation            testGameContract testGameCore
//   testAdapterPersistence  testAdapterAi           testAdapterEvent
//   testGameEventContract   testGameHistoryDelivery
//   testAdapterRestContract testAdapterRestHttp4s
//   testAdapterWebsocket    testAdapterGui          testAdapterTui
//   testStartupShared       testGameService       testHistoryService testAiService
//   testDesktopGui          testTuiCli
//
// Grouped aliases by architectural concern:
//   testCore         — domain + notation + game-contract + game-core
//   testInfra        — adapter-persistence + event modules + adapter-ai + adapter-websocket
//   testRest         — adapter-rest-contract + adapter-rest-http4s
//   testUi           — adapter-gui + adapter-tui
//   testAllAdapters  — all adapter modules
//   testApps         — startup-shared + game-service + history-service + ai-service + desktop-gui + tui-cli
//
// Compile slice aliases:
//   compileCore      — domain + notation + game-contract + game-core
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
addCommandAlias("testObservability",      "observability/test")
addCommandAlias("testNotation",           "notation/test")
addCommandAlias("testGameContract",       "gameContract/test")
addCommandAlias("testAiContract",         "aiContract/test")
addCommandAlias("testGameCore",           "gameCore/test")
addCommandAlias("testHistory",            "history/test")
addCommandAlias("testAdapterPersistence", "adapterPersistence/test")
addCommandAlias("testMigration",          "migration/test")
addCommandAlias("testAdapterAi",          "adapterAi/test")
addCommandAlias("testAdapterEvent",       "adapterEvent/test")
addCommandAlias("testGameEventContract",  "gameEventContract/test")
addCommandAlias("testGameHistoryDelivery","gameHistoryDelivery/test")
addCommandAlias("testAdapterRestContract","adapterRestContract/test")
addCommandAlias("testAdapterRestHttp4s",  "adapterRestHttp4s/test")
addCommandAlias("testAdapterWebsocket",   "adapterWebsocket/test")
addCommandAlias("testAdapterGui",         "adapterGui/test")
addCommandAlias("testAdapterTui",         "adapterTui/test")
addCommandAlias("testStartupShared",      "startupShared/test")
addCommandAlias("testGameService",        "gameService/test")
addCommandAlias("testHistoryService",     "historyService/test")
addCommandAlias("testAiService",          "aiService/test")
addCommandAlias("testDesktopGui",         "desktopGui/test")
addCommandAlias("testTuiCli",             "tuiCli/test")

// ── Grouped test: by architectural concern ────────────────────────────────────

addCommandAlias("testCore",
  ";domain/test;observability/test;notation/test;gameContract/test;aiContract/test;gameCore/test;history/test")

addCommandAlias("testInfra",
  ";adapterPersistence/test;migration/test;adapterEvent/test;gameEventContract/test;gameHistoryDelivery/test" +
  ";adapterAi/test;adapterWebsocket/test")

addCommandAlias("testRest",
  ";adapterRestContract/test;adapterRestHttp4s/test")

addCommandAlias("testUi",
  ";adapterGui/test;adapterTui/test")

addCommandAlias("testAllAdapters",
  ";adapterPersistence/test;migration/test;adapterEvent/test;gameEventContract/test;gameHistoryDelivery/test" +
  ";adapterAi/test;adapterWebsocket/test" +
  ";adapterRestContract/test;adapterRestHttp4s/test" +
  ";adapterGui/test;adapterTui/test")

addCommandAlias("testApps",
  ";startupShared/test;gameService/test;historyService/test;aiService/test;desktopGui/test;tuiCli/test")

// ── Compile slices ────────────────────────────────────────────────────────────

addCommandAlias("compileCore",
  ";domain/compile;observability/compile;notation/compile;gameContract/compile;aiContract/compile;gameCore/compile")

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
    domain, observability, notation, gameContract, aiContract, gameCore, history,
    adapterPersistence, migration, adapterAi, adapterEvent, gameEventContract, gameHistoryDelivery,
    adapterRestContract, adapterRestHttp4s,
    adapterWebsocket, adapterGui, adapterTui,
    startupShared, gameService, historyService, aiService, desktopGui, tuiCli
  )
