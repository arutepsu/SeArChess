package chess.domain.rules.application

import chess.domain.error.DomainError
import chess.domain.model.{Board, Color, Move, MoveResult, Piece, PieceType}
import chess.domain.model.positionstate.{CastlingRights, EnPassantState}
import chess.domain.rules.validation.{CastlingValidator, CheckValidator, EnPassantValidator, MoveValidator}

object MoveApplier:

  /** Apply a move to a board, enforcing legal movement rules and king safety.
   *
   *  Returns:
   *    - Right(Applied(board))                          — move completed normally
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
   *    - CastleNotAllowed    — castling right is not available
   *    - MissingCastlingRook — rook is not on its original square
   *    - CastlePathBlocked   — squares between king and rook are occupied
   *    - CastleThroughCheck  — king starts, passes through, or lands on attacked square
   *    - InvalidEnPassant    — en passant preconditions not met
   *
   *  Branch dispatch order:
   *    1. empty source
   *    2. castling  (King + castling geometry)
   *    3. en passant (Pawn + active en passant state + matching target)
   *    4. normal move
   */
  def applyMove(
      board:          Board,
      move:           Move,
      castlingRights: CastlingRights         = CastlingRights.none,
      enPassantState: Option[EnPassantState] = None
  ): Either[DomainError, MoveResult] =
    board.pieceAt(move.from) match
      case None =>
        Left(DomainError.EmptySourceSquare(move.from))

      case Some(piece @ Piece(color, PieceType.King)) if CastlingValidator.isCastlingMove(move) =>
        CastlingValidator.validate(board, color, move, castlingRights).map { kingSide =>
          MoveResult.Applied(CastlingApplier.applyCastle(board, color, kingSide))
        }

      case Some(capturingPiece) if EnPassantValidator.isEnPassantMove(board, move, enPassantState) =>
        val ep = enPassantState.get  // safe: isEnPassantMove guarantees Some
        EnPassantValidator.validate(board, move, ep).flatMap { _ =>
          val newBoard = EnPassantApplier.applyEnPassant(board, move, ep)
          Either.cond(
            !CheckValidator.isKingInCheck(newBoard, capturingPiece.color),
            MoveResult.Applied(newBoard),
            DomainError.KingInCheck
          )
        }

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
