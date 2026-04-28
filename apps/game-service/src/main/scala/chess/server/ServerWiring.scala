package chess.server

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.semigroupk.*
import chess.adapter.http4s.Http4sApp
import chess.server.assembly.{AppContext, EventWiring}
import chess.server.config.{AiConfig, AppConfig}
import chess.server.http.{CorsMiddleware, HealthRoutes, HistoryOutboxOpsRoutes, MigrationAdminRoutes}
import chess.server.migration.MigrationCliRunner
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.{HttpApp, Request}

/** Starts the Game Service HTTP runtime from service-owned composition. */
object ServerWiring:

  def start(config: AppConfig): (AppContext, ServerRuntime) =
    val (ctx, events) = GameServiceComposition.assemble(config)

    val publicGameplayApi: HttpApp[IO] =
      Http4sApp(
        ctx.gameService,
        ctx.persistentSessionService,
        ctx.snapshotTransferService,
        ctx.gameRepository,
        ctx.sessionGameStore
      ).httpApp

    val baseOpsRoutes = HealthRoutes.routes <+> HistoryOutboxOpsRoutes(events.historyOutbox).routes
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

    val httpApp: HttpApp[IO] =
      CorsMiddleware(config.cors, composedApp)

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
