package chess.adapter.gui.animation

import chess.domain.model.{Color, PieceType, Position}

/** Timings for a capture animation sequence.
 *
 *  A capture is presented as:
 *    Approach -> Attack -> Attack1 -> Dead -> Fade
 */
final case class CapturePhaseTimings(
  approachMs: Int = 400,
  attackMs:   Int = 500,
  attack1Ms:  Int = 500,
  deadMs:     Int = 1000,
  fadeMs:     Int = 450
):
  def totalMs: Int = approachMs + attackMs + attack1Ms + deadMs + fadeMs

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
 *  @param moveDurationMs total wall-clock duration for a non-capture move
 *  @param captureTimings phase timings used when this plan is a capture
 */
final case class AnimationPlan(
  movingPiece:    (Color, PieceType),
  from:           Position,
  to:             Position,
  capturedPiece:  Option[(Color, PieceType)],
  moveDurationMs: Int = 340,
  captureTimings: CapturePhaseTimings = CapturePhaseTimings()
):
  def isCapture: Boolean = capturedPiece.isDefined

  /** Total animation duration in milliseconds. */
  def durationMs: Int =
    if isCapture then captureTimings.totalMs else moveDurationMs