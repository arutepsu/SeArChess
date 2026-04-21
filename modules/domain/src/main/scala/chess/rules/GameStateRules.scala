package chess.domain.rules

import chess.domain.error.DomainError
import chess.domain.model.{Board, Color, GameStatus, Move, MoveResult, Piece, PieceType, Position}
import chess.domain.rules.application.MoveApplier
import chess.domain.rules.evaluation.GameStatusEvaluator
import chess.domain.rules.state.CastlingRightsUpdater
import chess.domain.state.{EnPassantState, GameState}

/** Public domain rule boundary.
  *
  * The canonical entry point for all chess rule queries and state transitions. Application and
  * adapter layers must use this object rather than composing raw rule objects directly.
  */
object GameStateRules:

  /** All legal target squares that the current-player piece at `from` can move to, including
    * promotion targets. Returns an empty set when there is no current-player piece at `from`.
    */
  def legalTargetsFrom(state: GameState, from: Position): Set[Position] =
    LegalMoveGenerator.legalTargetsFrom(state, from)

  /** All legal [[Move]] objects from `from` in the current state.
    *
    * Promotion moves are expanded into four moves (Q / R / B / N). Returns an empty set when there
    * is no current-player piece at `from`.
    */
  def legalMovesFrom(state: GameState, from: Position): Set[Move] =
    LegalMoveGenerator.legalMovesFrom(state, from)

  /** All legal [[Move]] objects for the current player across the entire board. */
  def legalMoves(state: GameState): Set[Move] =
    state.board.pieces
      .collect { case (pos, piece) if piece.color == state.currentPlayer => pos }
      .flatMap(pos => LegalMoveGenerator.legalMovesFrom(state, pos))
      .toSet

  /** Re-evaluate the game status for the current state. */
  def evaluateStatus(state: GameState): GameStatus =
    GameStatusEvaluator.evaluate(state)

  /** Apply `move` to `state`, deriving the full next [[GameState]].
    *
    * Handles all pure state derivation: board update, castling rights, en passant lifecycle,
    * halfmove clock, fullmove number, and status re-evaluation. Returns
    * `Left(MissingPromotionChoice)` when a pawn reaches the last rank without a promotion piece
    * specified.
    *
    * Does NOT perform application-layer guard checks (e.g. turn enforcement).
    */
  def applyMove(state: GameState, move: Move): Either[DomainError, GameState] =
    val movedPiece = state.board.pieceAt(move.from)
    val capturedOpt = state.board.pieceAt(move.to)
    val nextRights = CastlingRightsUpdater.update(state.castlingRights, state.board, move)
    MoveApplier
      .applyMove(state.board, move, state.castlingRights, state.enPassantState)
      .flatMap {
        case MoveResult.Applied(newBoard) =>
          val nextEnPassant = computeEnPassantState(move, state.board)
          val nextPlayer = state.currentPlayer.opposite
          val nextStatus =
            GameStatusEvaluator.evaluate(newBoard, nextPlayer, nextRights, nextEnPassant)
          val nextHalfmove = computeHalfmoveClock(state.halfmoveClock, movedPiece, capturedOpt)
          val nextFullmove =
            if state.currentPlayer == Color.Black then state.fullmoveNumber + 1
            else state.fullmoveNumber
          Right(
            state.copy(
              board = newBoard,
              currentPlayer = nextPlayer,
              moveHistory = state.moveHistory :+ move,
              status = nextStatus,
              castlingRights = nextRights,
              enPassantState = nextEnPassant,
              halfmoveClock = nextHalfmove,
              fullmoveNumber = nextFullmove
            )
          )

        case MoveResult.PromotionRequired(_, _, _) =>
          Left(DomainError.MissingPromotionChoice)
      }

  // ── en passant lifecycle ────────────────────────────────────────────────────

  /** Derive en passant state from a just-completed move.
    *
    * A two-square pawn advance creates an EnPassantState for the opponent's immediate reply. Every
    * other move produces None, which clears any existing en passant availability.
    */
  private def computeEnPassantState(move: Move, boardBefore: Board): Option[EnPassantState] =
    boardBefore.pieceAt(move.from).flatMap {
      case Piece(color, PieceType.Pawn) =>
        val (fromRank, toRank, passedRank) =
          if color == Color.White then (1, 3, 2) else (6, 4, 5)
        if move.from.rank == fromRank && move.to.rank == toRank then
          Position.from(move.from.file, passedRank).toOption.map(EnPassantState(_, move.to, color))
        else None
      case _ => None
    }

  /** Reset on pawn move or capture; increment otherwise. */
  private def computeHalfmoveClock(
      current: Int,
      movedPiece: Option[Piece],
      captured: Option[Piece]
  ): Int =
    val isPawnMove = movedPiece.exists(_.pieceType == PieceType.Pawn)
    val isCapture = captured.isDefined
    if isPawnMove || isCapture then 0 else current + 1
