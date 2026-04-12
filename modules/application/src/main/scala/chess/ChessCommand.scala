package chess.application

import chess.domain.model.Move

enum ChessCommand:
  case NewGame
  case MakeMove(move: Move)
