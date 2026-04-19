package chess.server

import chess.adapter.event.CollectingEventPublisher
import chess.application.ai.service.AITurnError
import chess.application.session.model.{SessionMode, SideController}
import chess.config.{AppConfig, CorsConfig, EventMode, HttpConfig, PersistenceMode, WebSocketConfig}
import chess.startup.assembly.{CoreAssembly, EventWiring, PersistenceAssembly}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ServerWiringSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val config = AppConfig(
    http        = HttpConfig("127.0.0.1", 8080),
    webSocket   = WebSocketConfig(enabled = false, port = 9090),
    persistence = PersistenceMode.InMemory,
    eventMode   = EventMode.InProcess,
    cors        = CorsConfig(enabled = false, allowedOrigin = "*")
  )

  "ServerWiring.withServerAi" should "configure the Game Service AI endpoint path" in {
    val persistence = PersistenceAssembly.assemble(config)
    val collector   = CollectingEventPublisher()
    val events      = EventWiring(collector, wsServer = None)
    val baseCtx     = CoreAssembly.build(persistence, events)
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
    val baseCtx     = CoreAssembly.build(persistence, events)
    val (_, session) = baseCtx.gameService.createGame(
      mode            = SessionMode.HumanVsAI,
      whiteController = SideController.AI(),
      blackController = SideController.HumanLocal
    ).value

    baseCtx.gameService.triggerAIMoveByGameId(session.gameId).left.value shouldBe AITurnError.NotConfigured
  }
