package chess.domain.rules

import chess.domain.error.DomainError
import chess.domain.model.{Board, Move}

object MoveApplier:

  /** Apply a move to a board, enforcing legal movement rules and king safety.
   *
   *  Fails with:
   *    - EmptySourceSquare   — no piece at the source
   *    - SameSquare          — source and target are identical
   *    - OccupiedByOwnPiece  — target holds a piece of the same color
   *    - IllegalMove         — movement pattern is invalid for the piece type
   *    - BlockedPath         — a sliding piece's path is obstructed
   *    - KingInCheck         — the move would leave or place own king in check
   */
  def applyMove(board: Board, move: Move): Either[DomainError, Board] =
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
        yield newBoard
