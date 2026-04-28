package chess.server

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.semigroupk.*
<<<<<<< HEAD
<<<<<<< HEAD
=======
import chess.adapter.ai.LocalDeterministicAiClient
import chess.adapter.ai.remote.RemoteAiMoveSuggestionClient
>>>>>>> 14542117 (fix ai flow)
import chess.adapter.http4s.Http4sApp
<<<<<<< HEAD
import chess.server.assembly.{AppContext, EventWiring}
import chess.server.config.{AiConfig, AppConfig}
import chess.adapter.http4s.DomainMetricsRegistry
import chess.server.http.{CorsMiddleware, HealthRoutes, HistoryOutboxOpsRoutes, HttpMetricsMiddleware, HttpMetricsRegistry, HttpRequestLoggingMiddleware, MigrationAdminRoutes}
import chess.server.http.MetricsRoutes
import chess.server.migration.MigrationCliRunner
=======
import chess.application.DefaultGameService
import chess.application.ai.service.AITurnService
import chess.application.port.ai.AiMoveSuggestionClient
import chess.config.{AiConfig, AiProviderMode, AppConfig}
import chess.server.assembly.{EventAssembly, EventWiring}
import chess.server.http.{CorsMiddleware, HealthRoutes, HistoryOutboxOpsRoutes}
import chess.startup.assembly.{AppContext, CoreAssembly, PersistenceAssembly}
>>>>>>> abcc8c8c (envoy + ai service prerp)
=======
import chess.adapter.http4s.Http4sApp
import chess.server.assembly.{AppContext, EventWiring}
import chess.server.config.{AiConfig, AppConfig}
<<<<<<< HEAD
import chess.server.http.{CorsMiddleware, HealthRoutes, HistoryOutboxOpsRoutes}
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
=======
import chess.server.http.{CorsMiddleware, HealthRoutes, HistoryOutboxOpsRoutes, MigrationAdminRoutes}
import chess.server.migration.MigrationCliRunner
>>>>>>> 2b1aa125 (real migration ok)
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.{HttpApp, Request}

/** Starts the Game Service HTTP runtime from service-owned composition. */
object ServerWiring:

  def start(config: AppConfig): (AppContext, ServerRuntime) =
    val (ctx, events) = GameServiceComposition.assemble(config)

<<<<<<< HEAD
    val metricsRegistry = new HttpMetricsRegistry
    val domainMetrics   = new DomainMetricsRegistry

    val publicGameplayApi: HttpApp[IO] =
      Http4sApp(
        ctx.gameService,
        ctx.persistentSessionService,
        ctx.snapshotTransferService,
        ctx.gameRepository,
        ctx.sessionGameStore,
        domainMetrics
      ).httpApp

<<<<<<< HEAD
    val baseOpsRoutes = HealthRoutes.routes <+> MetricsRoutes.routes(metricsRegistry, domainMetrics) <+> HistoryOutboxOpsRoutes(events.historyOutbox).routes
    val internalOpsRoutes =
      if config.migrationAdminEnabled then
        val token = config.migrationAdminToken.getOrElse(
          throw RuntimeException("migrationAdminToken must be set when migrationAdminEnabled — config validation should prevent this state")
        )
        baseOpsRoutes <+> MigrationAdminRoutes(token, MigrationCliRunner.runForReport(_)).routes
      else
        baseOpsRoutes
=======
    val publicGameplayApi: HttpApp[IO] =
      Http4sApp(ctx.gameService).httpApp
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)

=======
    val baseOpsRoutes = HealthRoutes.routes <+> HistoryOutboxOpsRoutes(events.historyOutbox).routes
>>>>>>> 2b1aa125 (real migration ok)
    val internalOpsRoutes =
      if config.migrationAdminEnabled then
        val token = config.migrationAdminToken.getOrElse(
          throw RuntimeException("migrationAdminToken must be set when migrationAdminEnabled — config validation should prevent this state")
        )
        baseOpsRoutes <+> MigrationAdminRoutes(token, MigrationCliRunner.runForReport(_)).routes
      else
        baseOpsRoutes

    val composedApp: HttpApp[IO] =
      Kleisli { (req: Request[IO]) =>
        internalOpsRoutes
          .run(req)
          .getOrElseF(publicGameplayApi.run(req))
      }

    val loggedApp: HttpApp[IO] =
      HttpRequestLoggingMiddleware(composedApp)

    val instrumentedApp: HttpApp[IO] =
      HttpMetricsMiddleware(metricsRegistry, loggedApp)

    val httpApp: HttpApp[IO] =
      CorsMiddleware(config.cors, instrumentedApp)

<<<<<<< HEAD
    val host = Host
      .fromString(config.http.host)
=======
    val host = Host.fromString(config.http.host)
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
      .getOrElse(throw RuntimeException(s"[chess] Invalid HTTP host: '${config.http.host}'"))
    val port = Port
      .fromInt(config.http.port)
      .getOrElse(
        throw RuntimeException(s"[chess] HTTP port out of ip4s range: ${config.http.port}")
      )

    val (_, shutdownHttp) =
      EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpApp(httpApp)
        .build
        .allocated
        .unsafeRunSync()

    (ctx, ServerRuntime(events.wsServer, shutdownHttp, IO { events.shutdown(); ctx.shutdownPersistence() }))

<<<<<<< HEAD
<<<<<<< HEAD
=======
  /** Attach the server's configured AI move-suggestion client to the Game Service boundary.
   *
   *  `CoreAssembly` intentionally leaves AI disabled for shared GUI/TUI
   *  composition. The HTTP server exposes `/games/{id}/ai-move`, so this runtime
   *  wires the configured AI client through the single AI port and turn service.
   */
>>>>>>> 14542117 (fix ai flow)
  private[server] def withServerAi(baseCtx: AppContext, events: EventWiring): AppContext =
<<<<<<< HEAD
    GameServiceComposition.withAi(baseCtx, events)
=======
    withServerAi(
      baseCtx,
      events,
      AiConfig(AiProviderMode.Remote, Some(chess.config.RemoteAiConfig("http://ai-service:8765")), 2000, None)
    )
>>>>>>> abcc8c8c (envoy + ai service prerp)
=======
  private[server] def withServerAi(baseCtx: AppContext, events: EventWiring): AppContext =
    GameServiceComposition.withAi(baseCtx, events)
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)

  private[server] def withServerAi(
      baseCtx: AppContext,
      events: EventWiring,
      config: AiConfig
  ): AppContext =
<<<<<<< HEAD
<<<<<<< HEAD
    GameServiceComposition.withAi(baseCtx, events, config)

<<<<<<< HEAD
  private[server] def aiClientFor(
      config: AiConfig
  ): Option[chess.application.port.ai.AiMoveSuggestionClient] =
    GameServiceComposition.aiClientFor(config)
=======
  private[server] def aiProviderFor(config: AiConfig): Option[AiMoveSuggestionClient] =
=======
    val aiService = aiClientFor(config).map(client =>
      AITurnService(client, baseCtx.commands, events.publisher))
    baseCtx.copy(gameService = DefaultGameService(
      commands       = baseCtx.commands,
      sessionService = baseCtx.sessionService,
      gameRepository = baseCtx.gameRepository,
      publisher      = events.publisher,
      aiService      = aiService
    ))

  private[server] def aiClientFor(config: AiConfig): Option[AiMoveSuggestionClient] =
>>>>>>> 14542117 (fix ai flow)
    config.mode match
      case AiProviderMode.LocalDeterministic => Some(LocalDeterministicAiClient())
      case AiProviderMode.Disabled           => None
      case AiProviderMode.Remote             =>
        config.remote match
          case Some(remote) =>
            Some(RemoteAiMoveSuggestionClient(
              baseUrl         = remote.baseUrl,
              timeoutMillis   = config.timeoutMillis,
              defaultEngineId = config.defaultEngineId,
              testMode        = remote.testMode
            ))
          case None =>
            throw IllegalArgumentException("AI remote mode requires AI_REMOTE_BASE_URL")
>>>>>>> abcc8c8c (envoy + ai service prerp)
=======
    GameServiceComposition.withAi(baseCtx, events, config)

  private[server] def aiClientFor(config: AiConfig): Option[chess.application.port.ai.AiMoveSuggestionClient] =
    GameServiceComposition.aiClientFor(config)
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
