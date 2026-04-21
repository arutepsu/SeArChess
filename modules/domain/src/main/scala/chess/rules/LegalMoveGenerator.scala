package chess.domain.rules

import chess.domain.model.{Move, Piece, PieceType, Position}
import chess.domain.state.GameState
import chess.domain.rules.application.MoveApplier
import chess.domain.model.MoveResult

/** Generates all legal moves for a position.
  *
  * Promotion moves are expanded: a pawn move to the last rank yields four canonical moves (one for
  * each promotable piece type), rather than one incomplete move with no promotion piece.
  */
object LegalMoveGenerator:

  private val promotionPieces: List[PieceType] =
    List(PieceType.Queen, PieceType.Rook, PieceType.Bishop, PieceType.Knight)

  private lazy val allSquares: Seq[Position] =
    for
      f <- 0 to 7
      r <- 0 to 7
      pos <- Position.from(f, r).toOption.toSeq
    yield pos

  /** All legal moves from `from` in the current game state.
    *
    * Promotion moves are expanded into four moves (Q / R / B / N). Returns an empty set if there is
    * no current-player piece at `from`.
    */
  def legalMovesFrom(state: GameState, from: Position): Set[Move] =
    state.board.pieceAt(from) match
      case Some(piece) if piece.color == state.currentPlayer =>
        allSquares.flatMap { to =>
          MoveApplier.applyMove(
            state.board,
            Move(from, to),
            state.castlingRights,
            state.enPassantState
          ) match
            case Right(MoveResult.PromotionRequired(_, _, _)) =>
              promotionPieces.map(pt => Move(from, to, Some(pt)))
            case Right(MoveResult.Applied(_)) =>
              List(Move(from, to))
            case Left(_) =>
              List.empty
        }.toSet
      case _ => Set.empty

  /** All legal target squares from `from` (without promotion expansion). */
  def legalTargetsFrom(state: GameState, from: Position): Set[Position] =
    state.board.pieceAt(from) match
      case Some(piece) if piece.color == state.currentPlayer =>
        allSquares.filter { to =>
          MoveApplier
            .applyMove(state.board, Move(from, to), state.castlingRights, state.enPassantState)
            .isRight
        }.toSet
      case _ => Set.empty
