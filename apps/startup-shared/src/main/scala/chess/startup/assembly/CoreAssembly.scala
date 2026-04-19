package chess.startup.assembly

import chess.adapter.event.NoOpEventPublisher
import chess.application.DefaultGameService
import chess.application.GameServiceApi
import chess.application.port.repository.GameRepository
import chess.application.session.service.{GameSessionCommands, SessionGameService, SessionService}
import chess.config.AppConfig

/** Stable shared application runtime produced by [[CoreAssembly.build]].
 *
 *  Contains only the application-level services that are common to every
 *  app project (server, GUI, TUI, test).  Does not carry HTTP handles,
 *  WebSocket server references, or any other runtime-specific state.
 *
 *  @param commands       write boundary for game-session mutations ([[GameSessionCommands]])
 *  @param sessionService session read/query and lifecycle service
 *  @param gameRepository read-only game-state port
 *  @param gameService    unified Game Service boundary ([[GameServiceApi]])
 */
final case class AppContext(
  commands:       GameSessionCommands,
  sessionService: SessionService,
  gameRepository: GameRepository,
  gameService:    GameServiceApi
)

/** Assembles the shared application runtime from infrastructure wiring.
 *
 *  Responsible only for wiring persistence and event infrastructure into
 *  application services.  Does not start servers, create connections, or own
 *  any runtime lifecycle — those are app-project concerns owned by
 *  [[chess.server.ServerWiring]], [[chess.guiapp.GuiWiring]],
 *  [[chess.tuiapp.TuiWiring]], and similar.
 *
 *  === Primary entry point ===
 *  [[build(PersistenceWiring, EventWiring)]] is the preferred method.
 *  The caller (e.g. [[chess.server.ServerWiring]]) assembles infrastructure and passes it in,
 *  keeping full control of what publisher and server handles are created.
 *
 *  === Convenience entry point ===
 *  [[build(AppConfig)]] is for GUI/TUI app deployment and tests.
 *  It uses a [[NoOpEventPublisher]] — events are accepted and silently discarded,
 *  which is correct for standalone GUI and TUI apps that use [[ObservableGame]]
 *  directly and start no WebSocket server.
 *  Do not use it for server deployment.
 */
object CoreAssembly:

  /** Build the shared application context from pre-assembled infrastructure.
   *
   *  The preferred call path.  The caller controls which [[EventWiring]]
   *  (and therefore which [[chess.application.port.event.EventPublisher]]) is
   *  injected into the services.  When called from [[chess.server.ServerWiring]],
   *  the publisher is backed by the same registry as the live WebSocket server so
   *  that events flow to connected clients.
   *
   *  The [[DefaultGameService]] created here has no AI provider; callers that
   *  need AI support (e.g. the server wiring) must replace or extend the
   *  [[GameServiceApi]] instance after construction.
   */
  def build(persistence: PersistenceWiring, events: EventWiring): AppContext =
    val sessionService = SessionService(persistence.sessionRepository, events.publisher)
    val commands       = SessionGameService(sessionService, persistence.store, events.publisher)
    val gameService    = DefaultGameService(
                           commands       = commands,
                           sessionService = sessionService,
                           gameRepository = persistence.gameRepository,
                           publisher      = events.publisher,
                           aiService      = None
                         )
    AppContext(commands, sessionService, persistence.gameRepository, gameService)

  /** Build the application context for GUI/TUI app deployment and tests.
   *
   *  Calls [[PersistenceAssembly]] and injects a [[NoOpEventPublisher]] — events
   *  are accepted and silently discarded.  No WebSocket server is started.
   *  GUI and TUI apps receive game state updates through [[ObservableGame]]
   *  directly, not through the event publishing path.
   *
   *  For server deployment, use [[chess.server.ServerWiring.start]] instead.
   */
  def build(config: AppConfig): AppContext =
    val persistence = PersistenceAssembly.assemble(config)
    val events      = EventWiring(NoOpEventPublisher, wsServer = None)
    build(persistence, events)
