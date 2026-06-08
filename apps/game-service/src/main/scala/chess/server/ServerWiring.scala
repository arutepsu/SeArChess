package chess.server

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.semigroupk.*
import chess.adapter.http4s.Http4sApp
import chess.adapter.http4s.route.{BotCredentials, BotSessionRateLimiter, ExternalGameRouteAuth, HttpAuthenticatedUserClient, HttpHistoryArchiveClient, InMemoryBotSessionRateLimiter}
import chess.application.external.VerifiedExternalCaller
import chess.application.session.model.ExternalPlatform
import chess.server.assembly.{AppContext, EventWiring}
import chess.server.config.{AiConfig, AppConfig, ExternalGameBotConfig}
import chess.adapter.http4s.DomainMetricsRegistry
import chess.server.http.{CorsMiddleware, HealthRoutes, HistoryOutboxOpsRoutes, HttpMetricsMiddleware, HttpMetricsRegistry, HttpRequestLoggingMiddleware, MigrationAdminRoutes}
import chess.server.http.MetricsRoutes
import chess.server.migration.MigrationCliRunner
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
      buildPublicGameplayApi(ctx, config, domainMetrics)

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
    GameServiceComposition.withAi(baseCtx, events)

  private[server] def withServerAi(
      baseCtx: AppContext,
      events: EventWiring,
      config: AiConfig
  ): AppContext =
    GameServiceComposition.withAi(baseCtx, events, config)

  private[server] def aiClientFor(
      config: AiConfig
  ): Option[chess.application.port.ai.AiMoveSuggestionClient] =
    GameServiceComposition.aiClientFor(config)

  private[server] def buildPublicGameplayApi(
      ctx: AppContext,
      config: AppConfig,
      domainMetrics: DomainMetricsRegistry = new DomainMetricsRegistry
  ): HttpApp[IO] =
    val botRateLimiter: BotSessionRateLimiter = InMemoryBotSessionRateLimiter(
      config.botGameRateLimit.limitPerWindow,
      config.botGameRateLimit.windowSeconds
    )
    Http4sApp(
      ctx.gameService,
      ctx.persistentSessionService,
      ctx.snapshotTransferService,
      ctx.gameRepository,
      ctx.sessionGameStore,
      domainMetrics,
      externalGameService = ctx.externalGameService,
      externalGameAuth = externalGameAuth(config.externalGameBot),
      userClient = Some(HttpAuthenticatedUserClient(config.userService.baseUrl, config.userService.timeoutMillis)),
      historyArchiveClient = config.history.baseUrl.map(url => HttpHistoryArchiveClient(url, config.history.timeoutMillis)),
      botRateLimiter = Some(botRateLimiter)
    ).httpApp

  private[server] def externalGameAuth(
      config: Option[ExternalGameBotConfig]
  ): Option[ExternalGameRouteAuth] =
    config.flatMap { bot =>
      parseExternalPlatform(bot.platform).map { platform =>
        ExternalGameRouteAuth(
          List(
            BotCredentials(
              apiKey = bot.apiKey,
              caller = VerifiedExternalCaller(platform, bot.actorId)
            )
          )
        )
      }
    }

  private def parseExternalPlatform(value: String): Option[ExternalPlatform] =
    value.trim.toLowerCase match
      case "lichess" => Some(ExternalPlatform.Lichess)
      case _         => None
