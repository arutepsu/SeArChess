package chess.domain.error

sealed trait DomainError

object DomainError:
  final case class OutOfBounds(file: Int, rank: Int)
    extends DomainError

  final case class InvalidPositionString(input: String)
    extends DomainError

  final case class EmptySourceSquare(position: String)
    extends DomainError
