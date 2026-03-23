package chess.domain.rules.application

import chess.domain.error.DomainError
import chess.domain.model.{Board, Color, Piece, PieceType, Position}

/** Finalises a pending promotion by replacing the pawn at `square` with the
 *  chosen piece.
 *
 *  Validates all domain invariants independently of application state:
 *    - a piece must exist at `square`
 *    - that piece must be a Pawn
 *    - the pawn must match the expected `color`
 *    - `square` must be on the correct promotion rank for that color
 *    - `pieceType` must be Queen, Rook, Bishop, or Knight
 */
object PromotionApplier:

  def applyPromotion(
      board: Board,
      square: Position,
      color: Color,
      pieceType: PieceType
  ): Either[DomainError, Board] =
    val promotionRank = if color == Color.White then 7 else 0
    val validState    = board.pieceAt(square).exists(p =>
      p.pieceType == PieceType.Pawn && p.color == color
    ) && square.rank == promotionRank
    for
      _ <- Either.cond(validState, (), DomainError.InvalidPromotionState)
      _ <- Either.cond(
             pieceType != PieceType.King && pieceType != PieceType.Pawn,
             (),
             DomainError.InvalidPromotionPiece
           )
    yield board.place(square, Piece(color, pieceType))
