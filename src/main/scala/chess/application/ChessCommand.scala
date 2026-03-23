package chess.application

import chess.domain.model.{Move, PieceType}

enum ChessCommand:
  case NewGame
  case MakeMove(move: Move)
  case Promote(pieceType: PieceType)
