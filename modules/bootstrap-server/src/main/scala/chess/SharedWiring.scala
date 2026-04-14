package chess

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.adapter.http4s.Http4sApp
import chess.adapter.websocket.ChessWebSocketServer
import chess.application.port.repository.GameRepository
import chess.application.session.service.{GameSessionCommands, SessionGameService, SessionService}
import chess.config.AppConfig
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder

/** Live backend handles produced by [[SharedWiring.start]].
 *
 *  Carries the service references and shutdown callbacks shared between the
 *  server-only ([[ServerMain]]) and desktop-combined ([[DesktopMain]])
 *  compositions.
 *
 *  Fields are typed to stable application interfaces where possible;
 *  callers are not exposed to concrete implementation classes.
 *
 *  @param commands       write boundary for game-session mutations ([[GameSessionCommands]])
 *  @param sessionService session read/query and lifecycle service
 *  @param gameRepository read-only game-state port
 *  @param wsServer       live WebSocket server, if WebSocket was enabled in config;
 *                        call `stop(0)` on the contained value to shut down
 *  @param shutdownHttp   effect that cleanly terminates the HTTP server
 */
final case class BackendWiring(
  commands:       GameSessionCommands,
  sessionService: SessionService,
  gameRepository: GameRepository,
  wsServer:       Option[ChessWebSocketServer],
  shutdownHttp:   IO[Unit]
)

/** Shared backend wiring helper.
 *
 *  Assembles the infrastructure that is common to every deployment mode and
 *  expresses it as a linear, readable composition:
 *
 *   1. persistence assembly ([[PersistenceAssembly]])
 *   2. event distribution assembly ([[EventAssembly]])
 *   3. application services
 *   4. HTTP server startup
 *
 *  Each assembly concern is fully delegated to its own object.
 *  `SharedWiring` only threads the resulting wiring records into the layers
 *  that depend on them.
 */
object SharedWiring:

  /** Start all backend infrastructure and return live handles.
   *
   *  Does NOT start the GUI or TUI.  The caller is responsible for shutdown
   *  via the handles in the returned [[BackendWiring]].
   *
   *  `SessionGameService` is constructed here as the concrete implementation of
   *  [[GameSessionCommands]], but exported through the interface so callers do
   *  not depend on the concrete class.
   *
   *  Fails fast (throws) if `config.http.host` cannot be parsed as a valid
   *  hostname or IP address.  Port range is already validated by
   *  [[chess.config.ConfigLoader]], so [[Port.fromInt]] will not fail in practice.
   */
  def start(config: AppConfig): BackendWiring =

    // ── Persistence assembly ─────────────────────────────────────────────────
    val persistence = PersistenceAssembly.assemble(config)

    // ── Event distribution assembly ──────────────────────────────────────────
    // EventAssembly decides which publishers are wired based on config.eventMode
    // and config.webSocket.  The assembled publisher is injected into the
    // application services; the optional wsServer handle is forwarded for
    // shutdown.
    val events = EventAssembly.assemble(config)

    // ── Application service layer ────────────────────────────────────────────
    // fanOut is shared: SessionService uses it for createSession/preparePromotion
    // events; SessionGameService uses it for move-related events published after
    // the combined session+game-state write completes.
    val sessionService     = SessionService(persistence.sessionRepository, events.publisher)
    val sessionGameService = SessionGameService(sessionService, persistence.store, events.publisher)

    // ── REST server ──────────────────────────────────────────────────────────
    // Resolve config strings to ip4s types; port is already range-validated by
    // ConfigLoader so Port.fromInt will not fail in practice.
    val host = Host.fromString(config.http.host)
      .getOrElse(throw RuntimeException(s"[chess] Invalid HTTP host: '${config.http.host}'"))
    val port = Port.fromInt(config.http.port)
      .getOrElse(throw RuntimeException(s"[chess] HTTP port out of ip4s range: ${config.http.port}"))

    // Http4sApp composes routes; EmberServerBuilder owns the lifecycle here.
    // The IO[Unit] returned by .allocated shuts the server down cleanly.
    val httpApp = Http4sApp(sessionGameService, sessionService, persistence.gameRepository).httpApp
    val (_, shutdownHttp) =
      EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpApp(httpApp)
        .build
        .allocated
        .unsafeRunSync()

    BackendWiring(sessionGameService, sessionService, persistence.gameRepository, events.wsServer, shutdownHttp)
