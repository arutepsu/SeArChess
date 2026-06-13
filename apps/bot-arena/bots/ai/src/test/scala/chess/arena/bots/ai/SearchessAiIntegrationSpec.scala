package chess.arena.bots.ai

import chess.domain.state.GameStateFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SearchessAiIntegrationSpec extends AnyFlatSpec with Matchers:

  private val maybeBaseUrl: Option[String] = sys.env.get("SEARCHESS_AI_BASE_URL")

  "SearchessAiBot with live service" should "return a legal move from the initial position" in {
    assume(
      maybeBaseUrl.isDefined,
      "Set SEARCHESS_AI_BASE_URL to enable this integration test (e.g. http://localhost:8765)"
    )
    val config = AiServiceBotConfig.fromEnv()
    val bot    = SearchessAiBot(config, HttpSearchessAiClient(config))
    val state  = GameStateFactory.initial()

    val move = bot.selectMove(state)
    move.from.toString should have length 2
    move.to.toString   should have length 2
  }
