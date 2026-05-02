package chess.domain.rules.application

import chess.domain.model.{Board, Color, Position}
import chess.domain.rules.validation.CastlingValidator

/** Applies a validated castling move by relocating both the king and the rook.
  *
  * Precondition: CastlingValidator.validate has already succeeded.
  */
object CastlingApplier:

  private def p(file: Int, rank: Int): Position =
    Position
      .from(file, rank)
      .getOrElse(throw AssertionError(s"Invalid castling constant: file=$file rank=$rank"))

  def applyCastle(board: Board, color: Color, kingSide: Boolean): Board =
    val r = if color == Color.White then 0 else 7
    val kingFrom = p(4, r) // e1 / e8
    val kingTo = if kingSide then p(6, r) else p(2, r) // g or c file
    val rookFrom = CastlingValidator.rookOrigin(color, kingSide)
    val rookTo = CastlingValidator.rookDestination(color, kingSide)

    val king = board
      .pieceAt(kingFrom)
      .getOrElse(throw AssertionError("King missing after CastlingValidator"))
    val rook = board
      .pieceAt(rookFrom)
      .getOrElse(throw AssertionError("Rook missing after CastlingValidator"))

    board.moveTwoPieces(kingFrom, kingTo, king, rookFrom, rookTo, rook)
