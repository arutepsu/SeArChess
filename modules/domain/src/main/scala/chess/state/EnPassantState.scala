package chess.domain.state

import chess.domain.model.{Color, Position}

/** Tracks en passant availability for exactly one half-move.
 *
 *  Created immediately after a pawn makes a two-square advance and
 *  cleared after the opponent's next move (regardless of what that move is).
 *
 *  @param targetSquare         the empty square the capturing pawn moves into
 *  @param capturablePawnSquare the square currently occupied by the pawn that may be captured
 *  @param pawnColor            the color of the pawn that may be captured (not the capturer)
 */
final case class EnPassantState(
  targetSquare:         Position,
  capturablePawnSquare: Position,
  pawnColor:            Color
)
