package chess.startup.assembly

import chess.application.DefaultGameService
import chess.application.GameServiceApi
import chess.application.event.AppEvent
import chess.application.port.event.{EventPublisher, NoOpTerminalEventJsonSerializer, TerminalEventJsonSerializer}
import chess.application.port.repository.GameRepository
import chess.application.session.service.{GameSessionCommands, SessionGameService, SessionService}
import chess.config.AppConfig

/** Neutral event dependencies required by the shared application core.
 *
 *  This is intentionally smaller than the Game Service runtime event assembly:
 *  it carries only the application-layer publisher and the optional terminal
 *  event serializer needed for durable outbox writes. It does not know about
 *  WebSocket servers, History forwarder lifecycle, HTTP routes, or any other
 *  service process handles.
 */
final case class CoreEventBindings(
  publisher:          EventPublisher,
  terminalSerializer: TerminalEventJsonSerializer = NoOpTerminalEventJsonSerializer
)

/** Stable shared application runtime produced by [[CoreAssembly.build]].
 *
 *  Contains only the application-level services that are common to every
 *  app project (server, GUI, TUI, test). Does not carry HTTP handles,
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
 *  Responsible only for wiring persistence and neutral event dependencies into
 *  application services. It does not start servers, create WebSocket registries,
 *  drain outboxes, or own runtime lifecycle. Those are app-project concerns
 *  owned by [[chess.server.ServerWiring]], [[chess.guiapp.GuiWiring]],
 *  [[chess.tuiapp.TuiWiring]], and similar.
 *
 *  === Primary entry point ===
 *  [[build(PersistenceWiring, CoreEventBindings)]] is the preferred method.
 *  The caller assembles infrastructure and passes it in, keeping full control
 *  of runtime handles and service-specific lifecycle.
 *
 *  === Convenience entry point ===
 *  [[build(AppConfig)]] is for GUI/TUI app deployment and tests. It uses a
 *  silent local publisher, which is correct for standalone GUI and TUI apps
 *  that use [[ObservableGame]] directly and start no WebSocket server. Do not
 *  use it for server deployment.
 */
object CoreAssembly:

  /** Build the shared application context from pre-assembled infrastructure.
   *
   *  The caller controls which [[chess.application.port.event.EventPublisher]]
   *  and [[chess.application.port.event.TerminalEventJsonSerializer]] are
   *  injected into the services. Service-specific runtime handles, such as the
   *  WebSocket server and History outbox forwarder, are owned by the app that
   *  starts them.
   *
   *  The [[DefaultGameService]] created here has no AI provider; callers that
   *  need AI support (e.g. the Game Service HTTP runtime) attach it in their
   *  own composition root after construction.
   */
  def build(persistence: PersistenceWiring, events: CoreEventBindings): AppContext =
    val sessionService = SessionService(persistence.sessionRepository, events.publisher, events.terminalSerializer)
    val commands       = SessionGameService(sessionService, persistence.store, events.publisher, events.terminalSerializer)
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
   *  Calls [[PersistenceAssembly]] and injects a silent local publisher. No
   *  WebSocket server is started. GUI and TUI apps receive game state updates
   *  through [[ObservableGame]] directly, not through the event publishing path.
   *
   *  The [[chess.application.port.event.NoOpTerminalEventJsonSerializer]]
   *  default in [[CoreEventBindings]] means no outbox writes are performed in
   *  this mode.
   */
  def build(config: AppConfig): AppContext =
    val persistence = PersistenceAssembly.assemble(config)
    val events      = CoreEventBindings(SilentEventPublisher)
    build(persistence, events)

  private object SilentEventPublisher extends EventPublisher:
    def publish(event: AppEvent): Unit = ()
