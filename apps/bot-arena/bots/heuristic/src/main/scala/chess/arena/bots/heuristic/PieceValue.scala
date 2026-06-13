package chess.arena.bots.heuristic

import chess.domain.model.PieceType

object PieceValue:
  def of(pieceType: PieceType): Int = pieceType match
    case PieceType.Pawn   => 1
    case PieceType.Knight => 3
    case PieceType.Bishop => 3
    case PieceType.Rook   => 5
    case PieceType.Queen  => 9
    case PieceType.King   => 0
