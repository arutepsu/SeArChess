package chess.server

import chess.adapter.ai.LocalDeterministicAiClient
import chess.adapter.ai.remote.RemoteAiMoveSuggestionClient
import chess.application.DefaultGameService
import chess.application.ai.service.AITurnService
import chess.application.port.ai.AiMoveSuggestionClient
import chess.server.assembly.{
  AppContext,
  CoreAssembly,
  EventAssembly,
  EventWiring,
  PersistenceAssembly
}
import chess.server.config.{AiConfig, AiProviderMode, AppConfig}

/** Game Service composition root.
  *
  * Owns the Game Service application wiring: persistence, event runtime bindings, and the
  * configured AI client attached to the public service boundary. Transport startup remains in
  * [[ServerWiring]].
  */
object GameServiceComposition:

  def assemble(config: AppConfig): (AppContext, EventWiring) =
    val persistence = PersistenceAssembly.assemble(config)
    val events = EventAssembly.assemble(config)
    val baseCtx = CoreAssembly.build(persistence, events.coreEvents)
    (withAi(baseCtx, events, config.ai), events)

  private[server] def withAi(baseCtx: AppContext, events: EventWiring): AppContext =
    withAi(
      baseCtx,
      events,
      AiConfig(
        AiProviderMode.Remote,
        Some(chess.server.config.RemoteAiConfig("http://ai-service:8765")),
        2000,
        None
      )
    )

  private[server] def withAi(
      baseCtx: AppContext,
      events: EventWiring,
      config: AiConfig
  ): AppContext =
    val aiService =
      aiClientFor(config).map(client => AITurnService(client, baseCtx.commands, events.publisher))
    baseCtx.copy(gameService =
      DefaultGameService(
        commands = baseCtx.commands,
        sessionService = baseCtx.sessionService,
        gameRepository = baseCtx.gameRepository,
        publisher = events.publisher,
        aiService = aiService
      )
    )

  private[server] def aiClientFor(config: AiConfig): Option[AiMoveSuggestionClient] =
    config.mode match
      case AiProviderMode.LocalDeterministic => Some(LocalDeterministicAiClient())
      case AiProviderMode.Disabled           => None
      case AiProviderMode.Remote =>
        config.remote match
          case Some(remote) =>
            Some(
              RemoteAiMoveSuggestionClient(
                baseUrl = remote.baseUrl,
                timeoutMillis = config.timeoutMillis,
                defaultEngineId = config.defaultEngineId,
                testMode = remote.testMode
              )
            )
          case None =>
            throw IllegalArgumentException("AI remote mode requires AI_REMOTE_BASE_URL")
