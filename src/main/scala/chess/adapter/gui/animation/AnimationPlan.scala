package chess.adapter.gui.animation

import chess.domain.model.{Color, PieceType, Position}

/** Pure data describing a single-piece move animation.
 *
 *  The planner captures all required information at planning time using
 *  before/after board snapshots, so the renderer never needs to consult
 *  chess state.
 *
 *  @param movingPiece    colour and type of the piece travelling from → to
 *  @param from           source square (empty in the post-move board)
 *  @param to             destination square (contains [[movingPiece]] in the post-move board)
 *  @param capturedPiece  the piece that occupied [[to]] before the move, if any
 *  @param durationMs     total wall-clock duration of the animation in milliseconds
 */
final case class AnimationPlan(
  movingPiece:   (Color, PieceType),
  from:          Position,
  to:            Position,
  capturedPiece: Option[(Color, PieceType)],
  durationMs:    Int = 220
):
  def isCapture: Boolean = capturedPiece.isDefined
