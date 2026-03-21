package chess.domain.rules

import chess.domain.error.DomainError
import chess.domain.model.{Board, Color, Move, MoveResult, PieceType}

object MoveApplier:

  /** Apply a move to a board, enforcing legal movement rules and king safety.
   *
   *  Returns:
   *    - Right(Applied(board))                       — move completed normally
   *    - Right(PromotionRequired(board, square, color)) — pawn reached the last
   *      rank; the board has the pawn at the promotion square but the caller
   *      must still choose a promotion piece
   *
   *  Fails with:
   *    - EmptySourceSquare   — no piece at the source
   *    - SameSquare          — source and target are identical
   *    - OccupiedByOwnPiece  — target holds a piece of the same color
   *    - IllegalMove         — movement pattern is invalid for the piece type
   *    - BlockedPath         — a sliding piece's path is obstructed
   *    - KingInCheck         — the move would leave or place own king in check
   */
  def applyMove(board: Board, move: Move): Either[DomainError, MoveResult] =
    board.pieceAt(move.from) match
      case None =>
        Left(DomainError.EmptySourceSquare(move.from))
      case Some(piece) =>
        for
          _        <- MoveValidator.validate(board, piece, move)
          newBoard  = board.remove(move.from).place(move.to, piece)
          _        <- Either.cond(
                        !CheckValidator.isKingInCheck(newBoard, piece.color),
                        (),
                        DomainError.KingInCheck
                      )
        yield
          val promotionRank = if piece.color == Color.White then 7 else 0
          if piece.pieceType == PieceType.Pawn && move.to.rank == promotionRank then
            MoveResult.PromotionRequired(newBoard, move.to, piece.color)
          else
            MoveResult.Applied(newBoard)
