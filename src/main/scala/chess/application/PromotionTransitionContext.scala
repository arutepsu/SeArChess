package chess.application

import chess.domain.model.{Color, GameStatus, Move, Piece, PieceType, Position}

/** Bundles the pre-computed facts needed to build events for a completed promotion transition.
 *
 *  Pure data holder — no methods, no logic.
 */
final case class PromotionTransitionContext(
  square:        Position,
  color:         Color,
  move:          Move,
  capturedPiece: Option[Piece],
  pieceType:     PieceType,
  prevStatus:    GameStatus,
  nextStatus:    GameStatus,
  nextPlayer:    Color
)
