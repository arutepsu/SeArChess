package chess.domain.rules

import chess.domain.error.DomainError
import chess.domain.model.{Board, Move}

object MoveApplier:

  /** Apply a move to a board without checking chess legality.
   *
   *  - Returns Left(EmptySourceSquare) if the source square has no piece.
   *  - Otherwise removes the piece from the source and places it at the
   *    target, overwriting any piece already there.
   */
  def applyMove(board: Board, move: Move): Either[DomainError, Board] =
    board.pieceAt(move.from) match
      case None =>
        Left(DomainError.EmptySourceSquare(move.from.toString))
      case Some(piece) =>
        Right(board.remove(move.from).place(move.to, piece))
