package chess.arena.bots.ai

import chess.adapter.ai.remote.{RemoteAiMoveDto, RemoteAiMoveSuggestionRequest, RemoteAiMoveSuggestionResponse}
import chess.arena.events.BotFamily
import chess.domain.state.GameStateFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SearchessAiBotSpec extends AnyFlatSpec with Matchers:

  private val config = AiServiceBotConfig(
    baseUrl       = "http://localhost:8765",
    endpointPath  = "/v1/move-suggestions",
    timeoutMillis = 2000L,
    botId         = "searchess-ai-v1",
    modelVersion  = "v1",
    engineId      = None,
    gameIdPrefix  = "test"
  )

  private val initialState = GameStateFactory.initial()

  private def fakeClient(
      response: Either[SearchessAiClientError, RemoteAiMoveDto]
  ): SearchessAiClient =
    new SearchessAiClient:
      def suggestMove(
          request: RemoteAiMoveSuggestionRequest
      ): Either[SearchessAiClientError, RemoteAiMoveSuggestionResponse] =
        response.map(move => RemoteAiMoveSuggestionResponse(requestId = request.requestId, move = move))

  "SearchessAiBot.profile" should "have correct botId" in {
    val bot = SearchessAiBot(config, fakeClient(Right(RemoteAiMoveDto("e2", "e4"))))
    bot.profile.botId shouldBe "searchess-ai-v1"
  }

  it should "report family as AiService" in {
    val bot = SearchessAiBot(config, fakeClient(Right(RemoteAiMoveDto("e2", "e4"))))
    bot.profile.family shouldBe BotFamily.AiService
  }

  it should "report correct strategyType, engineType, and modelVersion" in {
    val bot = SearchessAiBot(config, fakeClient(Right(RemoteAiMoveDto("e2", "e4"))))
    bot.profile.strategyType shouldBe "searchess-ai"
    bot.profile.engineType   shouldBe "none"
    bot.profile.modelVersion shouldBe "v1"
  }

  "SearchessAiBot.selectMove" should "return the domain Move matching the AI response" in {
    val bot  = SearchessAiBot(config, fakeClient(Right(RemoteAiMoveDto("e2", "e4"))))
    val move = bot.selectMove(initialState)
    move.from.toString shouldBe "e2"
    move.to.toString   shouldBe "e4"
  }

  it should "pass legal moves to the client request" in {
    val captured = collection.mutable.ListBuffer.empty[RemoteAiMoveSuggestionRequest]
    val capturingClient = new SearchessAiClient:
      def suggestMove(
          request: RemoteAiMoveSuggestionRequest
      ): Either[SearchessAiClientError, RemoteAiMoveSuggestionResponse] =
        captured += request
        Right(RemoteAiMoveSuggestionResponse(request.requestId, request.legalMoves.head))

    val bot = SearchessAiBot(config, capturingClient)
    bot.selectMove(initialState)
    captured                        should have length 1
    captured.head.legalMoves        should not be empty
    captured.head.sideToMove        shouldBe "white"
    captured.head.metadata.mode     shouldBe "BotArena"
  }

  it should "throw IllegalStateException when the AI returns an illegal move" in {
    val bot = SearchessAiBot(config, fakeClient(Right(RemoteAiMoveDto("e2", "e6"))))
    an[IllegalStateException] should be thrownBy bot.selectMove(initialState)
  }

  it should "throw IllegalStateException when the client returns a connection error" in {
    val bot = SearchessAiBot(
      config,
      fakeClient(Left(SearchessAiClientError.ConnectionFailed("Connection refused")))
    )
    an[IllegalStateException] should be thrownBy bot.selectMove(initialState)
  }

  it should "throw IllegalStateException when the client returns a timeout error" in {
    val bot = SearchessAiBot(
      config,
      fakeClient(Left(SearchessAiClientError.Timeout("Read timeout")))
    )
    an[IllegalStateException] should be thrownBy bot.selectMove(initialState)
  }
