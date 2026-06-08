package chess.server.assembly

import chess.application.DefaultGameService
import chess.application.GameServiceApi
import chess.application.event.AppEvent
import chess.application.external.ExternalGameServiceApi
import chess.application.port.event.{
  EventPublisher,
  NoOpTerminalEventJsonSerializer,
  TerminalEventJsonSerializer
}
import chess.application.bot.BotTurnTaskRepository
import chess.application.port.repository.{GameRepository, SessionGameStore, SessionRepository}
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
    sessionRepository: SessionRepository,
    gameService: GameServiceApi,
    externalGameService: Option[ExternalGameServiceApi] = None,
    botTurnTaskRepository: Option[BotTurnTaskRepository] = None,
    shutdownPersistence: () => Unit = () => ()
)

/** Wires Game Service application services from service-owned infrastructure. */
object CoreAssembly:

  def build(
      persistence: PersistenceWiring,
      events: CoreEventBindings,
      botWorkerActorId: String = "searchess-bot"
  ): AppContext =
    val sessionLifecycleService =
      SessionLifecycleService(persistence.sessionRepository, events.publisher, events.terminalSerializer)
    val commands = SessionGameCommandService(
      sessionLifecycleService,
      persistence.store,
      events.publisher,
      events.terminalSerializer,
      botTurnTaskRepository = persistence.botTurnTaskRepository,
      botActorId = botWorkerActorId
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
      persistence.sessionRepository,
      gameService,
      externalGameService = None,
      botTurnTaskRepository = persistence.botTurnTaskRepository,
      shutdownPersistence = persistence.shutdown
    )

  object SilentEventPublisher extends EventPublisher:
    def publish(event: AppEvent): Unit = ()

