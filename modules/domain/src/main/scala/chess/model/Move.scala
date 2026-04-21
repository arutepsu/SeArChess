package chess.domain.model

/** A move expressed as source, target position, and optional promotion piece.
  *
  * @param promotion
  *   present only for pawn moves that reach the last rank; indicates the piece type the pawn should
  *   be replaced with. `None` means the caller has not yet specified a promotion piece and the move
  *   cannot be completed until one is provided.
  */
final case class Move(from: Position, to: Position, promotion: Option[PieceType] = None)
