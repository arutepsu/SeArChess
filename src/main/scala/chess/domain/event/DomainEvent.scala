package chess.domain.event

import chess.domain.model.{Color, GameStatus, Move, Piece, PieceType, Position}

// ── Supporting value types ───────────────────────────────────────────────────

/** Describes a piece that was captured during a move. */
final case class CaptureInfo(piece: PieceType, color: Color, at: Position)

/** Which side a castling move was performed on. */
enum CastlingSide:
  case KingSide, QueenSide

// ── Domain events ────────────────────────────────────────────────────────────

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

  /** Self-contained record of a fully-resolved move transition.
   *
   *  Intended as the future canonical source for replay and PGN conversion.
   *  Emitted once per completed half-move (immediately after legacy events).
   *
   *  Current limitations (to be filled in as the model matures):
   *  - `castling`  is always `None` until castling detection is wired in
   *  - `enPassant` is always `false` until en passant detection is wired in
   */
  final case class MoveExecuted(
    move:      Move,
    piece:     PieceType,
    color:     Color,
    from:      Position,
    to:        Position,
    capture:   Option[CaptureInfo],
    promotion: Option[PieceType],
    castling:  Option[CastlingSide],
    enPassant: Boolean,
    check:     Boolean,
    checkmate: Boolean,
    stalemate: Boolean
  ) extends DomainEvent
