package chess.domain.rules.validation

import chess.domain.model.*

/** Determines whether a king is in check on a given board. */
object CheckValidator:

  /** True if the king of `color` is attacked by any opponent piece. */
  def isKingInCheck(board: Board, color: Color): Boolean =
    findKing(board, color).exists(isSquareAttacked(board, _, opponent(color)))

  /** True if `pos` is attacked by at least one piece of `byColor`. */
  def isSquareAttacked(board: Board, pos: Position, byColor: Color): Boolean =
    allPieces(board, byColor).exists { case (from, piece) =>
      MoveValidator.canAttack(board, piece, from, pos)
    }

  // ── helpers ────────────────────────────────────────────────────────────────

  private def findKing(board: Board, color: Color): Option[Position] =
    allPieces(board, color)
      .collectFirst { case (pos, Piece(`color`, PieceType.King)) => pos }

  private def allPieces(board: Board, color: Color): Seq[(Position, Piece)] =
    for
      f   <- 0 to 7
      r   <- 0 to 7
      pos <- Position.from(f, r).toOption.toSeq
      pc  <- board.pieceAt(pos).toSeq
      if pc.color == color
    yield (pos, pc)

  private def opponent(color: Color): Color = color match
    case Color.White => Color.Black
    case Color.Black => Color.White
