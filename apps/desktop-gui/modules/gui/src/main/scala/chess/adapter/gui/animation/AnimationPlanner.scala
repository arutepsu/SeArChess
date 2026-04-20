package chess.adapter.gui.animation

import chess.domain.model.{Board, Move, PieceType}

/** Pure function: derive an [[AnimationPlan]] from before/after board snapshots.
 *
 *  The planner MUST use the previous board snapshot to read any captured piece —
 *  it is already absent from the post-move board.
 *
 *  Castling is excluded: the king travels two squares horizontally, and the rook
 *  teleports.  Animating castling is deferred to a later phase.
 */
object AnimationPlanner:

  /** Build a plan for a normal move or capture.
   *
   *  Returns [[None]] when:
   *  - No piece occupies [[move.from]] in [[prevBoard]] (defensive; unreachable for legal moves).
   *  - The move is a castling move (king travels ≥ 2 files).
   */
  def plan(prevBoard: Board, move: Move): Option[AnimationPlan] =
    prevBoard.pieceAt(move.from).flatMap { piece =>
      val isCastling =
        piece.pieceType == PieceType.King &&
        Math.abs(move.to.file - move.from.file) >= 2
      if isCastling then None
      else
        val captured = prevBoard.pieceAt(move.to).map(p => (p.color, p.pieceType))
        Some(AnimationPlan(
          movingPiece   = (piece.color, piece.pieceType),
          from          = move.from,
          to            = move.to,
          capturedPiece = captured
        ))
    }
