package chess.startup.local

import chess.application.DefaultGameService
import chess.application.GameServiceApi
import chess.application.event.AppEvent
import chess.application.port.event.{EventPublisher, NoOpTerminalEventJsonSerializer}
import chess.application.port.repository.GameRepository
import chess.application.session.service.{GameSessionCommands, SessionGameService, SessionService}

final case class LocalAppContext(
    commands: GameSessionCommands,
    sessionService: SessionService,
    gameRepository: GameRepository,
    gameService: GameServiceApi
)

/** Application assembly for standalone local clients only. */
object LocalGameAssembly:

  def build(config: LocalRuntimeConfig): LocalAppContext =
    val persistence = LocalPersistenceAssembly.assemble(config)
    val publisher = SilentEventPublisher
    val serializer = NoOpTerminalEventJsonSerializer
    val sessionService = SessionService(persistence.sessionRepository, publisher, serializer)
    val commands = SessionGameService(sessionService, persistence.store, publisher, serializer)
    val gameService = DefaultGameService(
      commands = commands,
      sessionService = sessionService,
      gameRepository = persistence.gameRepository,
      publisher = publisher,
      aiService = None
    )
    LocalAppContext(commands, sessionService, persistence.gameRepository, gameService)

  private object SilentEventPublisher extends EventPublisher:
    def publish(event: AppEvent): Unit = ()
