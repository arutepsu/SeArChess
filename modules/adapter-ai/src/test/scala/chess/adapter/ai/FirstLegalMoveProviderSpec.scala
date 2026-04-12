package chess.adapter.ai

import chess.application.port.ai.AIError
import chess.domain.model.Color
import chess.domain.rules.GameStateRules
import chess.domain.state.{GameState, GameStateFactory}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FirstLegalMoveProviderSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val provider = FirstLegalMoveProvider()

  // ── suggestMove: legal position ────────────────────────────────────────────

  "FirstLegalMoveProvider.suggestMove" should "return a legal move in the initial position" in {
    val state    = GameStateFactory.initial()
    val response = provider.suggestMove(state).value
    val legal    = GameStateRules.legalMoves(state)
    legal should contain(response.move)
  }

  it should "return the same move on repeated calls (deterministic)" in {
    val state = GameStateFactory.initial()
    provider.suggestMove(state).value shouldBe provider.suggestMove(state).value
  }

  // ── suggestMove: no legal moves ────────────────────────────────────────────

  it should "return NoLegalMove when the board has no pieces for the current player" in {
    // An empty board leaves the current player (White) with no legal moves.
    import chess.domain.model.Board
    val emptyState = GameStateFactory.initial().copy(board = Board.empty)
    provider.suggestMove(emptyState).left.value shouldBe AIError.NoLegalMove
  }
