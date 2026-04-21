package chess.server

import chess.adapter.event.CollectingEventPublisher
import chess.adapter.ai.remote.RemoteAiMoveSuggestionClient
import chess.application.ai.service.AITurnError
import chess.application.session.model.{SessionMode, SideController}
import chess.server.config.{
<<<<<<< HEAD
  AiConfig,
  AiProviderMode,
  AppConfig,
  CorsConfig,
  EventMode,
  HttpConfig,
  HistoryForwardingConfig,
  PersistenceMode,
  WebSocketConfig
=======
  AiConfig, AiProviderMode, AppConfig, CorsConfig, EventMode, HttpConfig,
  HistoryForwardingConfig, PersistenceMode, WebSocketConfig
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
}
import chess.server.assembly.EventWiring
import chess.server.assembly.{CoreAssembly, PersistenceAssembly}
import org.scalatest.{EitherValues, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ServerWiringSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  private val config = AppConfig(
    http = HttpConfig("127.0.0.1", 8080),
    webSocket = WebSocketConfig(enabled = false, port = 9090),
    persistence = PersistenceMode.InMemory,
<<<<<<< HEAD
    sqlite = None,
    postgres = None,
    mongo = None,
    eventMode = EventMode.InProcess,
    cors = CorsConfig(enabled = false, allowedOrigin = "*"),
    history = HistoryForwardingConfig(enabled = false, baseUrl = None, timeoutMillis = 2000),
    ai = AiConfig(
      AiProviderMode.Remote,
      remote = Some(chess.server.config.RemoteAiConfig("http://ai-service:8765")),
      timeoutMillis = 2000,
      defaultEngineId = None
    )
=======
    sqlite      = None,
    eventMode   = EventMode.InProcess,
    cors        = CorsConfig(enabled = false, allowedOrigin = "*"),
    history     = HistoryForwardingConfig(enabled = false, baseUrl = None, timeoutMillis = 2000),
<<<<<<< HEAD
    ai          = AiConfig(AiProviderMode.Remote, remote = Some(chess.config.RemoteAiConfig("http://ai-service:8765")), timeoutMillis = 2000, defaultEngineId = None)
>>>>>>> abcc8c8c (envoy + ai service prerp)
=======
    ai          = AiConfig(AiProviderMode.Remote, remote = Some(chess.server.config.RemoteAiConfig("http://ai-service:8765")), timeoutMillis = 2000, defaultEngineId = None)
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
  )

  "ServerWiring.withServerAi" should "configure the Game Service AI endpoint path" in {
    val persistence = PersistenceAssembly.assemble(config)
<<<<<<< HEAD
    val collector = CollectingEventPublisher()
    val events = EventWiring(collector, wsServer = None)
    val baseCtx = CoreAssembly.build(persistence, events.coreEvents)
    val serverCtx = ServerWiring.withServerAi(
      baseCtx,
      events,
      AiConfig(
        AiProviderMode.LocalDeterministic,
        remote = None,
        timeoutMillis = 2000,
        defaultEngineId = None
      )
=======
    val collector   = CollectingEventPublisher()
    val events      = EventWiring(collector, wsServer = None)
    val baseCtx     = CoreAssembly.build(persistence, events.coreEvents)
    val serverCtx   = ServerWiring.withServerAi(
      baseCtx,
      events,
      AiConfig(AiProviderMode.LocalDeterministic, remote = None, timeoutMillis = 2000, defaultEngineId = None)
>>>>>>> abcc8c8c (envoy + ai service prerp)
    )

    val (_, session) = serverCtx.gameService
      .createGame(
        mode = SessionMode.HumanVsAI,
        whiteController = SideController.AI(),
        blackController = SideController.HumanLocal
      )
      .value

    serverCtx.gameService.triggerAIMoveByGameId(session.gameId).isRight shouldBe true
  }

  it should "leave the base Game Service context explicit about AI absence" in {
    val persistence = PersistenceAssembly.assemble(config)
    val events = EventWiring(CollectingEventPublisher(), wsServer = None)
    val baseCtx = CoreAssembly.build(persistence, events.coreEvents)
    val (_, session) = baseCtx.gameService
      .createGame(
        mode = SessionMode.HumanVsAI,
        whiteController = SideController.AI(),
        blackController = SideController.HumanLocal
      )
      .value

    baseCtx.gameService
      .triggerAIMoveByGameId(session.gameId)
      .left
      .value shouldBe AITurnError.NotConfigured
  }

  it should "leave AI unconfigured when server AI mode is disabled" in {
    val persistence = PersistenceAssembly.assemble(config)
    val events = EventWiring(CollectingEventPublisher(), wsServer = None)
    val baseCtx = CoreAssembly.build(persistence, events.coreEvents)
    val serverCtx = ServerWiring.withServerAi(
      baseCtx,
      events,
      AiConfig(AiProviderMode.Disabled, remote = None, timeoutMillis = 2000, defaultEngineId = None)
    )
    val (_, session) = serverCtx.gameService
      .createGame(
        mode = SessionMode.HumanVsAI,
        whiteController = SideController.AI(),
        blackController = SideController.HumanLocal
      )
      .value

    serverCtx.gameService
      .triggerAIMoveByGameId(session.gameId)
      .left
      .value shouldBe AITurnError.NotConfigured
  }

  it should "select the remote AI client when remote mode is configured" in {
<<<<<<< HEAD
    val client = ServerWiring.aiClientFor(
      AiConfig(
        mode = AiProviderMode.Remote,
        remote = Some(chess.server.config.RemoteAiConfig("http://ai.local")),
        timeoutMillis = 2000,
        defaultEngineId = Some("stockfish-default")
      )
    )

    client.value shouldBe a[RemoteAiMoveSuggestionClient]
  }

  it should "select the remote AI client by default" in {
    val persistence = PersistenceAssembly.assemble(config)
    val events = EventWiring(CollectingEventPublisher(), wsServer = None)
    val baseCtx = CoreAssembly.build(persistence, events.coreEvents)
    val serverCtx = ServerWiring.withServerAi(baseCtx, events)

    val (_, session) = serverCtx.gameService
      .createGame(
        mode = SessionMode.HumanVsAI,
        whiteController = SideController.AI(),
        blackController = SideController.HumanLocal
      )
      .value

    serverCtx.gameService
      .triggerAIMoveByGameId(session.gameId)
      .left
      .value shouldBe a[AITurnError.ProviderFailure]
=======
    val client = ServerWiring.aiClientFor(AiConfig(
      mode            = AiProviderMode.Remote,
      remote          = Some(chess.server.config.RemoteAiConfig("http://ai.local")),
      timeoutMillis   = 2000,
      defaultEngineId = Some("stockfish-default")
    ))

    client.value shouldBe a[RemoteAiMoveSuggestionClient]
>>>>>>> 14542117 (fix ai flow)
  }

  it should "select the remote AI client by default" in {
    val persistence = PersistenceAssembly.assemble(config)
    val events      = EventWiring(CollectingEventPublisher(), wsServer = None)
    val baseCtx     = CoreAssembly.build(persistence, events.coreEvents)
    val serverCtx   = ServerWiring.withServerAi(baseCtx, events)

    val (_, session) = serverCtx.gameService.createGame(
      mode            = SessionMode.HumanVsAI,
      whiteController = SideController.AI(),
      blackController = SideController.HumanLocal
    ).value

    serverCtx.gameService.triggerAIMoveByGameId(session.gameId).left.value shouldBe a[AITurnError.ProviderFailure]
  }
