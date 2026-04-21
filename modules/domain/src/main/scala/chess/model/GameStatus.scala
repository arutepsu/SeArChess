package chess.domain.model

enum GameStatus:
  case Ongoing(inCheck: Boolean)
  case Checkmate(winner: Color)
  case Draw(reason: DrawReason)

  /** The [[winner]] side's opponent deliberately conceded the game. */
  case Resigned(winner: Color)
