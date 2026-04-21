package chess.adapter.ai

import chess.application.port.ai.AIError
import chess.application.port.ai.AIRequestContext
import chess.application.session.model.{GameSession, SessionMode, SideController}
import chess.application.session.model.SessionIds.GameId
import chess.domain.model.Color
import chess.domain.rules.GameStateRules
import chess.domain.state.{GameState, GameStateFactory}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LocalDeterministicAiClientSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val provider = LocalDeterministicAiClient()

  private def context(state: GameState): AIRequestContext =
    AIRequestContext.fromSession(
      GameSession.create(
        GameId.random(),
        SessionMode.HumanVsAI,
        SideController.AI(),
        SideController.HumanLocal
      ),
      state,
      requestId = "test-request"
    )

  // ── suggestMove: legal position ────────────────────────────────────────────

  "LocalDeterministicAiClient.suggestMove" should "return a legal move in the initial position" in {
    val state = GameStateFactory.initial()
    val response = provider.suggestMove(context(state)).value
    val legal = GameStateRules.legalMoves(state)
    legal should contain(response.move)
  }

  it should "return the same move on repeated calls (deterministic)" in {
    val state = GameStateFactory.initial()
    provider.suggestMove(context(state)).value shouldBe provider.suggestMove(context(state)).value
  }

  // ── suggestMove: no legal moves ────────────────────────────────────────────

  it should "return NoLegalMove when the board has no pieces for the current player" in {
    // An empty board leaves the current player (White) with no legal moves.
    import chess.domain.model.Board
    val emptyState = GameStateFactory.initial().copy(board = Board.empty)
    provider.suggestMove(context(emptyState)).left.value shouldBe AIError.NoLegalMove
  }
