package chess.domain.model

enum GameStatus:
  case Ongoing(inCheck: Boolean)
  case Checkmate(winner: Color)
  case Draw(reason: DrawReason)
