package chess.domain.error

import chess.domain.model.Position

enum DomainError:
  case OutOfBounds(file: Int, rank: Int)
  case InvalidPositionString(input: String)
  case EmptySourceSquare(position: Position)
  case IllegalMove(from: Position, to: Position)
  case BlockedPath(from: Position, to: Position)
  case OccupiedByOwnPiece(position: Position)
  case SameSquare
  case KingInCheck
  case InvalidPromotionPiece
  case InvalidPromotionState
  case MissingPromotionChoice
  case CastleNotAllowed
  case MissingCastlingRook
  case CastlePathBlocked
  case CastleThroughCheck
  case InvalidEnPassant
