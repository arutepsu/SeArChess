package chess.domain.error

import chess.domain.model.Position

sealed trait DomainError

object DomainError:
  final case class OutOfBounds(file: Int, rank: Int)
    extends DomainError

  final case class InvalidPositionString(input: String)
    extends DomainError

  final case class EmptySourceSquare(position: Position)
    extends DomainError

  final case class IllegalMove(from: Position, to: Position)
    extends DomainError

  final case class BlockedPath(from: Position, to: Position)
    extends DomainError

  final case class OccupiedByOwnPiece(position: Position)
    extends DomainError

  case object SameSquare
    extends DomainError
