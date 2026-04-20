package chess.server

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.semigroupk.*
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
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.{HttpApp, Request}

/** Starts the Game Service HTTP runtime from service-owned composition. */
object ServerWiring:

  def start(config: AppConfig): (AppContext, ServerRuntime) =
    val (ctx, events) = GameServiceComposition.assemble(config)

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

    val baseOpsRoutes = HealthRoutes.routes <+> MetricsRoutes.routes(metricsRegistry, domainMetrics) <+> HistoryOutboxOpsRoutes(events.historyOutbox).routes
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

    val host = Host
      .fromString(config.http.host)
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

  private[server] def withServerAi(
      baseCtx: AppContext,
      events: EventWiring,
      config: AiConfig
  ): AppContext =
    GameServiceComposition.withAi(baseCtx, events, config)

<<<<<<< HEAD
  private[server] def aiClientFor(
      config: AiConfig
  ): Option[chess.application.port.ai.AiMoveSuggestionClient] =
    GameServiceComposition.aiClientFor(config)
=======
  private[server] def aiProviderFor(config: AiConfig): Option[AiMoveSuggestionClient] =
    config.mode match
      case AiProviderMode.LocalDeterministic => Some(FirstLegalMoveProvider())
      case AiProviderMode.Disabled           => None
      case AiProviderMode.Remote             =>
        config.remote match
          case Some(remote) =>
            Some(RemoteAiProvider(
              baseUrl         = remote.baseUrl,
              timeoutMillis   = config.timeoutMillis,
              defaultEngineId = config.defaultEngineId
            ))
          case None =>
            throw IllegalArgumentException("AI remote mode requires AI_REMOTE_BASE_URL")
>>>>>>> abcc8c8c (envoy + ai service prerp)
