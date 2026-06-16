package chess.arena.core

import chess.domain.model.{Move, PieceType}

object MoveUci:
  def encode(move: Move): String =
    val promo = move.promotion.fold("")(pieceTypeChar)
    s"${move.from}${move.to}$promo"

  private def pieceTypeChar(pt: PieceType): String = pt match
    case PieceType.Queen  => "q"
    case PieceType.Rook   => "r"
    case PieceType.Bishop => "b"
    case PieceType.Knight => "n"
    case _                => ""
