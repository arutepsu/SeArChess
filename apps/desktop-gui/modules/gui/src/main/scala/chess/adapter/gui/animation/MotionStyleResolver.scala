package chess.adapter.gui.animation

import chess.domain.model.PieceType

/** Maps a [[PieceType]] to the [[MotionStyle]] that best characterises its movement feel.
  *
  * This is the single place where per-piece animation policy is declared. The resolver is pure and
  * total — every [[PieceType]] has exactly one style.
  */
object MotionStyleResolver:

  /** Return the [[MotionStyle]] for the given [[pieceType]] and move context.
    *
    * @param pieceType
    *   the type of the moving piece
    * @param isCapture
    *   `true` when the move is a capture; the attacker uses [[MotionStyle.AttackLunge]] regardless
    *   of piece type
    */
  def resolve(pieceType: PieceType, isCapture: Boolean = false): MotionStyle =
    if isCapture then MotionStyle.AttackLunge(0.15)
    else
      pieceType match
        case PieceType.Pawn   => MotionStyle.Linear
        case PieceType.Rook   => MotionStyle.Heavy
        case PieceType.Knight => MotionStyle.Arc(0.5)
        case PieceType.Bishop => MotionStyle.Smooth
        case PieceType.Queen  => MotionStyle.Smooth
        case PieceType.King   => MotionStyle.Heavy
