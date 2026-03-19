package chess.domain.model

sealed trait GameStatus
object GameStatus:
  case object Ongoing   extends GameStatus
  case object Check     extends GameStatus
  case object Checkmate extends GameStatus
  case object Stalemate extends GameStatus
