package chess.domain.rules.validation

import chess.domain.model.*

/** Determines whether a king is in check on a given board. */
object CheckValidator:

  /** True if the king of `color` is attacked by any opponent piece. */
  def isKingInCheck(board: Board, color: Color): Boolean =
    findKing(board, color).exists(isSquareAttacked(board, _, color.opposite))

  /** True if `pos` is attacked by at least one piece of `byColor`. */
  def isSquareAttacked(board: Board, pos: Position, byColor: Color): Boolean =
    allPieces(board, byColor).exists { case (from, piece) =>
      MoveValidator.canAttack(board, piece, from, pos)
    }

  // ── helpers ────────────────────────────────────────────────────────────────

  private def findKing(board: Board, color: Color): Option[Position] =
    allPieces(board, color)
      .collectFirst { case (pos, Piece(`color`, PieceType.King)) => pos }

  private def allPieces(board: Board, color: Color): Iterator[(Position, Piece)] =
    board.piecesIterator.filter(_._2.color == color)
