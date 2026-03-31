package chess.application

import chess.domain.model.{Color, GameStatus, Move, Piece}
import chess.domain.state.EnPassantState

/** Bundles the pre-computed facts needed to build events for a completed move transition.
 *
 *  Pure data holder — no methods, no logic.
 */
final case class MoveTransitionContext(
  move:           Move,
  movedPiece:     Option[Piece],
  captured:       Option[Piece],
  enPassantState: Option[EnPassantState],
  prevStatus:     GameStatus,
  nextStatus:     GameStatus,
  nextPlayer:     Color
)
