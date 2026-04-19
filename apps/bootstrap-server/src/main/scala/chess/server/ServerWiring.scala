package chess.server

import cats.data.Kleisli
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.adapter.ai.FirstLegalMoveProvider
import chess.adapter.ai.remote.RemoteAiProvider
import chess.adapter.http4s.Http4sApp
import chess.application.DefaultGameService
import chess.application.ai.service.AITurnService
import chess.application.port.ai.AIProvider
import chess.config.{AiConfig, AiProviderMode, AppConfig}
import chess.server.http.{CorsMiddleware, HealthRoutes}
import chess.startup.assembly.{AppContext, CoreAssembly, EventAssembly, PersistenceAssembly}
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.{HttpApp, Request}

/** Assembles all server-specific runtime from [[AppConfig]].
 *
 *  Owns everything that is a server deployment concern:
 *
 *   1. Persistence infrastructure ([[PersistenceAssembly]])
 *   2. Event distribution infrastructure ([[EventAssembly]]), which starts the
 *      WebSocket server when enabled
 *   3. Shared application runtime ([[CoreAssembly]])
 *   4. HTTP surface composition (chess routes + health routes + CORS)
 *   5. Ember HTTP server startup
 *
 *  Returns the stable [[AppContext]] alongside the server runtime handles
 *  ([[ServerRuntime]]) so that callers can use application services without
 *  depending on server internals.
 *
 *  `CoreAssembly.build(persistence, events)` is used here — not the
 *  `build(config)` convenience — so that the event publisher wired into
 *  application services is the same one attached to the WebSocket server.
 */
object ServerWiring:

  /** Start all server infrastructure and return live handles.
   *
   *  Fails fast (throws) if `config.http.host` cannot be parsed as a valid
   *  hostname or IP address.  Port range is already validated by
   *  [[chess.config.ConfigLoader]], so [[Port.fromInt]] will not fail in practice.
   */
  def start(config: AppConfig): (AppContext, ServerRuntime) =

    // ── Infrastructure assembly ──────────────────────────────────────────────
    // EventAssembly starts the WebSocket server (when enabled) and produces
    // the publisher that is injected into the application service layer.
    // Both share the same registry, so they must be assembled together here.
    val persistence = PersistenceAssembly.assemble(config)
    val events      = EventAssembly.assemble(config)

    // ── Shared application context ───────────────────────────────────────────
    // CoreAssembly wires persistence and events into application services.
    // The overload that takes pre-assembled wiring is used here to ensure
    // the app services see the same publisher as the live WebSocket server.
    val baseCtx = CoreAssembly.build(persistence, events)
    val ctx     = withServerAi(baseCtx, events, config.ai)

    // ── HTTP surface composition ─────────────────────────────────────────────
    // Operational routes (health) are tried first; unmatched requests fall
    // through to the chess HttpApp.  CORS middleware wraps the entire surface.
    val chessHttpApp: HttpApp[IO] =
      Http4sApp(ctx.gameService).httpApp

    val composedApp: HttpApp[IO] =
      Kleisli { (req: Request[IO]) =>
        HealthRoutes.routes.run(req).getOrElseF(chessHttpApp.run(req))
      }

    val httpApp: HttpApp[IO] =
      CorsMiddleware(config.cors, composedApp)

    // ── HTTP server startup ──────────────────────────────────────────────────
    // Resolve config strings to ip4s types; port is already range-validated by
    // ConfigLoader so Port.fromInt will not fail in practice.
    val host = Host.fromString(config.http.host)
      .getOrElse(throw RuntimeException(s"[chess] Invalid HTTP host: '${config.http.host}'"))
    val port = Port.fromInt(config.http.port)
      .getOrElse(throw RuntimeException(s"[chess] HTTP port out of ip4s range: ${config.http.port}"))

    // The IO[Unit] returned by .allocated shuts the server down cleanly.
    val (_, shutdownHttp) =
      EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpApp(httpApp)
        .build
        .allocated
        .unsafeRunSync()

    (ctx, ServerRuntime(events.wsServer, shutdownHttp))

  /** Attach the server's configured AI provider to the Game Service boundary.
   *
   *  `CoreAssembly` intentionally leaves AI disabled for shared GUI/TUI
   *  composition. The HTTP server exposes `/games/{id}/ai-move`, so this runtime
   *  wires the existing AI adapter through the existing AI port and turn service.
   */
  private[server] def withServerAi(baseCtx: AppContext, events: chess.startup.assembly.EventWiring): AppContext =
    withServerAi(baseCtx, events, AiConfig(AiProviderMode.LocalDeterministic, None, 2000, None))

  private[server] def withServerAi(
    baseCtx: AppContext,
    events:  chess.startup.assembly.EventWiring,
    config:  AiConfig
  ): AppContext =
    val aiService = aiProviderFor(config).map(provider =>
      AITurnService(provider, baseCtx.commands, events.publisher))
    baseCtx.copy(gameService = DefaultGameService(
      commands       = baseCtx.commands,
      sessionService = baseCtx.sessionService,
      gameRepository = baseCtx.gameRepository,
      publisher      = events.publisher,
      aiService      = aiService
    ))

  private[server] def aiProviderFor(config: AiConfig): Option[AIProvider] =
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
