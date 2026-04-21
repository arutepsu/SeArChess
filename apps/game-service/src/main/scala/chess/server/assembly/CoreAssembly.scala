package chess.server.assembly

import chess.application.DefaultGameService
import chess.application.GameServiceApi
import chess.application.event.AppEvent
import chess.application.port.event.{EventPublisher, NoOpTerminalEventJsonSerializer, TerminalEventJsonSerializer}
import chess.application.port.repository.GameRepository
import chess.application.session.service.{GameSessionCommands, SessionGameService, SessionService}

final case class CoreEventBindings(
  publisher:          EventPublisher,
  terminalSerializer: TerminalEventJsonSerializer = NoOpTerminalEventJsonSerializer
)

final case class AppContext(
  commands:       GameSessionCommands,
  sessionService: SessionService,
  gameRepository: GameRepository,
  gameService:    GameServiceApi
)

/** Wires Game Service application services from service-owned infrastructure. */
object CoreAssembly:

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

  object SilentEventPublisher extends EventPublisher:
    def publish(event: AppEvent): Unit = ()
