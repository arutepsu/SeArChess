package chess.domain.rules.application

import chess.domain.model.{Board, Color, Position}
import chess.domain.rules.validation.CastlingValidator

/** Applies a validated castling move by relocating both the king and the rook.
 *
 *  Precondition: CastlingValidator.validate has already succeeded.
 */
object CastlingApplier:

  private def p(file: Int, rank: Int): Position = Position.from(file, rank).toOption.get

  def applyCastle(board: Board, color: Color, kingSide: Boolean): Board =
    val r       = if color == Color.White then 0 else 7
    val kingFrom = p(4, r)  // e1 / e8
    val kingTo   = if kingSide then p(6, r) else p(2, r)  // g or c file
    val rookFrom = CastlingValidator.rookOrigin(color, kingSide)
    val rookTo   = CastlingValidator.rookDestination(color, kingSide)

    val king = board.pieceAt(kingFrom).get  // guaranteed valid by CastlingValidator
    val rook = board.pieceAt(rookFrom).get

    board
      .remove(kingFrom).place(kingTo, king)
      .remove(rookFrom).place(rookTo, rook)
