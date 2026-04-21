package chess.application

import chess.domain.error.DomainError
import chess.domain.model.Move
import chess.domain.rules.GameStateRules
import chess.domain.state.GameState

/** Orchestrates move state transitions for a [[GameState]].
  *
  * Responsibilities:
  *   - delegate pure state derivation to [[GameStateRules.applyMove]]
  *   - capture pre-move context facts for event construction
  *   - delegate event construction to [[EventBuilder]]
  *   - return [[ApplyMoveResult]] on success or [[DomainError]] on failure
  *
  * Does NOT:
  *   - perform application-layer guard checks (turn enforcement)
  *   - own chess rule logic — that belongs in [[GameStateRules]]
  *   - map errors to [[ApplicationError]] — that is the façade's responsibility
  */
object GameTransitionService:

  def applyMoveWithEvents(state: GameState, move: Move): Either[DomainError, ApplyMoveResult] =
    val movedPiece = state.board.pieceAt(move.from)
    val capturedOpt = state.board.pieceAt(move.to)
    GameStateRules.applyMove(state, move).map { newState =>
      val ctx = MoveTransitionContext(
        move = move,
        movedPiece = movedPiece,
        captured = capturedOpt,
        enPassantState = state.enPassantState,
        prevStatus = state.status,
        nextStatus = newState.status,
        nextPlayer = newState.currentPlayer
      )
      ApplyMoveResult(newState, EventBuilder.buildMoveEvents(ctx))
    }
