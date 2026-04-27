package chess.startup.local

import chess.application.DefaultGameService
import chess.application.GameServiceApi
import chess.application.event.AppEvent
import chess.application.port.event.{EventPublisher, NoOpTerminalEventJsonSerializer}
import chess.application.port.repository.GameRepository
import chess.application.session.service.{
  GameSessionCommands,
  PersistentSessionService,
  SessionGameCommandService,
  SessionLifecycleService
}

final case class LocalAppContext(
    commands: GameSessionCommands,
    sessionLifecycleService: SessionLifecycleService,
    persistentSessionService: PersistentSessionService,
    gameRepository: GameRepository,
    gameService: GameServiceApi
)

/** Application assembly for standalone local clients only. */
object LocalGameAssembly:

  def build(config: LocalRuntimeConfig): LocalAppContext =
    val persistence = LocalPersistenceAssembly.assemble(config)
    val publisher = SilentEventPublisher
    val serializer = NoOpTerminalEventJsonSerializer
    val sessionLifecycleService = SessionLifecycleService(persistence.sessionRepository, publisher, serializer)
    val commands = SessionGameCommandService(sessionLifecycleService, persistence.store, publisher, serializer)
    val persistentSessionService = PersistentSessionService(
      persistence.sessionRepository,
      persistence.gameRepository,
      persistence.store,
      sessionLifecycleService
    )
    val gameService = DefaultGameService(
      commands = commands,
      sessionLifecycleService = sessionLifecycleService,
      gameRepository = persistence.gameRepository,
      publisher = publisher,
      aiService = None
    )
    LocalAppContext(
      commands,
      sessionLifecycleService,
      persistentSessionService,
      persistence.gameRepository,
      gameService
    )

  private object SilentEventPublisher extends EventPublisher:
    def publish(event: AppEvent): Unit = ()
