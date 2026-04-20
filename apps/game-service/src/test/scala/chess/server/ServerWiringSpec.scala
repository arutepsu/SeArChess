package chess.server

import chess.adapter.event.CollectingEventPublisher
import chess.adapter.ai.remote.RemoteAiProvider
import chess.application.ai.service.AITurnError
import chess.application.session.model.{SessionMode, SideController}
import chess.config.{
  AiConfig, AiProviderMode, AppConfig, CorsConfig, EventMode, HttpConfig,
  HistoryForwardingConfig, PersistenceMode, WebSocketConfig
}
import chess.server.assembly.EventWiring
import chess.startup.assembly.{CoreAssembly, PersistenceAssembly}
import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ServerWiringSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  private val config = AppConfig(
    http        = HttpConfig("127.0.0.1", 8080),
    webSocket   = WebSocketConfig(enabled = false, port = 9090),
    persistence = PersistenceMode.InMemory,
    sqlite      = None,
    eventMode   = EventMode.InProcess,
    cors        = CorsConfig(enabled = false, allowedOrigin = "*"),
    history     = HistoryForwardingConfig(enabled = false, baseUrl = None, timeoutMillis = 2000),
    ai          = AiConfig(AiProviderMode.LocalDeterministic, remote = None, timeoutMillis = 2000, defaultEngineId = None)
  )

  "ServerWiring.withServerAi" should "configure the Game Service AI endpoint path" in {
    val persistence = PersistenceAssembly.assemble(config)
    val collector   = CollectingEventPublisher()
    val events      = EventWiring(collector, wsServer = None)
    val baseCtx     = CoreAssembly.build(persistence, events.coreEvents)
    val serverCtx   = ServerWiring.withServerAi(baseCtx, events)

    val (_, session) = serverCtx.gameService.createGame(
      mode            = SessionMode.HumanVsAI,
      whiteController = SideController.AI(),
      blackController = SideController.HumanLocal
    ).value

    serverCtx.gameService.triggerAIMoveByGameId(session.gameId).isRight shouldBe true
  }

  it should "leave the shared CoreAssembly context explicit about AI absence" in {
    val persistence = PersistenceAssembly.assemble(config)
    val events      = EventWiring(CollectingEventPublisher(), wsServer = None)
    val baseCtx     = CoreAssembly.build(persistence, events.coreEvents)
    val (_, session) = baseCtx.gameService.createGame(
      mode            = SessionMode.HumanVsAI,
      whiteController = SideController.AI(),
      blackController = SideController.HumanLocal
    ).value

    baseCtx.gameService.triggerAIMoveByGameId(session.gameId).left.value shouldBe AITurnError.NotConfigured
  }

  it should "leave AI unconfigured when server AI mode is disabled" in {
    val persistence = PersistenceAssembly.assemble(config)
    val events      = EventWiring(CollectingEventPublisher(), wsServer = None)
    val baseCtx     = CoreAssembly.build(persistence, events.coreEvents)
    val serverCtx   = ServerWiring.withServerAi(
      baseCtx,
      events,
      AiConfig(AiProviderMode.Disabled, remote = None, timeoutMillis = 2000, defaultEngineId = None)
    )
    val (_, session) = serverCtx.gameService.createGame(
      mode            = SessionMode.HumanVsAI,
      whiteController = SideController.AI(),
      blackController = SideController.HumanLocal
    ).value

    serverCtx.gameService.triggerAIMoveByGameId(session.gameId).left.value shouldBe AITurnError.NotConfigured
  }

  it should "select the remote AI provider when remote mode is configured" in {
    val provider = ServerWiring.aiProviderFor(AiConfig(
      mode            = AiProviderMode.Remote,
      remote          = Some(chess.config.RemoteAiConfig("http://ai.local")),
      timeoutMillis   = 2000,
      defaultEngineId = Some("stockfish-default")
    ))

    provider.value shouldBe a[RemoteAiProvider]
  }
