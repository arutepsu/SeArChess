package chess.server.assembly

import chess.application.DefaultGameService
import chess.application.GameServiceApi
import chess.application.event.AppEvent
import chess.application.port.event.{
  EventPublisher,
  NoOpTerminalEventJsonSerializer,
  TerminalEventJsonSerializer
}
import chess.application.port.repository.{GameRepository, SessionGameStore}
import chess.application.session.service.{
  GameSessionCommands,
  PersistentSessionService,
  SessionSnapshotTransferService,
  SessionGameCommandService,
  SessionLifecycleService
}

final case class CoreEventBindings(
    publisher: EventPublisher,
    terminalSerializer: TerminalEventJsonSerializer = NoOpTerminalEventJsonSerializer
)

final case class AppContext(
    commands: GameSessionCommands,
    sessionLifecycleService: SessionLifecycleService,
    persistentSessionService: PersistentSessionService,
    snapshotTransferService: SessionSnapshotTransferService,
    sessionGameStore: SessionGameStore,
    gameRepository: GameRepository,
    gameService: GameServiceApi
)

/** Wires Game Service application services from service-owned infrastructure. */
object CoreAssembly:

  def build(persistence: PersistenceWiring, events: CoreEventBindings): AppContext =
    val sessionLifecycleService =
      SessionLifecycleService(persistence.sessionRepository, events.publisher, events.terminalSerializer)
    val commands = SessionGameCommandService(
      sessionLifecycleService,
      persistence.store,
      events.publisher,
      events.terminalSerializer
    )
    val persistentSessionService = PersistentSessionService(
      persistence.sessionRepository,
      persistence.gameRepository,
      persistence.store,
      sessionLifecycleService
    )
    val snapshotTransferService =
      SessionSnapshotTransferService(persistentSessionService, persistence.store)
    val gameService = DefaultGameService(
      commands = commands,
      sessionLifecycleService = sessionLifecycleService,
      gameRepository = persistence.gameRepository,
      publisher = events.publisher,
      aiService = None
    )
    AppContext(
      commands,
      sessionLifecycleService,
      persistentSessionService,
      snapshotTransferService,
      persistence.store,
      persistence.gameRepository,
      gameService
    )

  object SilentEventPublisher extends EventPublisher:
    def publish(event: AppEvent): Unit = ()

