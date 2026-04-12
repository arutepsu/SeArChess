package chess.adapter.gui.assets

import chess.domain.model.{Color, PieceType}

/** Maps a (Color, PieceType) pair to a Unicode chess glyph.
 *
 *  White pieces: U+2654–U+2659  (♔ ♕ ♖ ♗ ♘ ♙)
 *  Black pieces: U+265A–U+265F  (♚ ♛ ♜ ♝ ♞ ♟)
 *
 *  These render well with "Segoe UI Symbol" (Windows) and most system fonts.
 *  Swap this object when adding real sprite assets in a later phase.
 */
object PieceSymbol:

  def symbol(color: Color, pieceType: PieceType): String =
    (color, pieceType) match
      case (Color.White, PieceType.King)   => "♔"
      case (Color.White, PieceType.Queen)  => "♕"
      case (Color.White, PieceType.Rook)   => "♖"
      case (Color.White, PieceType.Bishop) => "♗"
      case (Color.White, PieceType.Knight) => "♘"
      case (Color.White, PieceType.Pawn)   => "♙"
      case (Color.Black, PieceType.King)   => "♚"
      case (Color.Black, PieceType.Queen)  => "♛"
      case (Color.Black, PieceType.Rook)   => "♜"
      case (Color.Black, PieceType.Bishop) => "♝"
      case (Color.Black, PieceType.Knight) => "♞"
      case (Color.Black, PieceType.Pawn)   => "♟"
