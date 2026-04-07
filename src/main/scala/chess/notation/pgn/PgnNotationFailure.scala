package chess.notation.pgn

sealed trait PgnNotationFailure
object PgnNotationFailure {
  case object InvalidFormat extends PgnNotationFailure
  case object NoMovesFound extends PgnNotationFailure
  case class Other(reason: String) extends PgnNotationFailure
}
