package chess.startup.local

import chess.application.DefaultGameService
import chess.application.GameServiceApi
import chess.application.event.AppEvent
import chess.application.port.event.{EventPublisher, NoOpTerminalEventJsonSerializer}
import chess.application.port.repository.GameRepository
<<<<<<< HEAD
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
=======
import chess.application.session.service.{GameSessionCommands, SessionGameService, SessionService}

final case class LocalAppContext(
  commands:       GameSessionCommands,
  sessionService: SessionService,
  gameRepository: GameRepository,
  gameService:    GameServiceApi
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
)

/** Application assembly for standalone local clients only. */
object LocalGameAssembly:

  def build(config: LocalRuntimeConfig): LocalAppContext =
    val persistence = LocalPersistenceAssembly.assemble(config)
<<<<<<< HEAD
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
=======
    val publisher   = SilentEventPublisher
    val serializer  = NoOpTerminalEventJsonSerializer
    val sessionService = SessionService(persistence.sessionRepository, publisher, serializer)
    val commands       = SessionGameService(sessionService, persistence.store, publisher, serializer)
    val gameService    = DefaultGameService(
                           commands       = commands,
                           sessionService = sessionService,
                           gameRepository = persistence.gameRepository,
                           publisher      = publisher,
                           aiService      = None
                         )
    LocalAppContext(commands, sessionService, persistence.gameRepository, gameService)
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)

  private object SilentEventPublisher extends EventPublisher:
    def publish(event: AppEvent): Unit = ()
