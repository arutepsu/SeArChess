package chess.domain.event

import chess.domain.model.{Color, GameStatus, Move, Piece, PieceType, Position}

sealed trait DomainEvent

object DomainEvent:
  final case class MoveApplied(move: Move)                                    extends DomainEvent
  final case class PieceCaptured(piece: Piece, at: Position)                  extends DomainEvent
  final case class PromotionRequired(at: Position, color: Color)              extends DomainEvent
  final case class Promoted(at: Position, color: Color, pieceType: PieceType) extends DomainEvent
  final case class CastlingPerformed(color: Color, kingSide: Boolean)         extends DomainEvent
  final case class EnPassantPerformed(move: Move, capturedAt: Position)       extends DomainEvent
  final case class CheckDeclared(color: Color)                                extends DomainEvent
  final case class GameStatusChanged(status: GameStatus)                      extends DomainEvent
