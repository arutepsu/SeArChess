package chess.domain.rules.evaluation

import chess.domain.model.*
import chess.domain.state.{CastlingRights, EnPassantState, GameState}
import chess.domain.rules.application.MoveApplier
import chess.domain.rules.validation.CheckValidator

/** Evaluates the game status (ongoing / checkmate / draw by stalemate) for the side to move on a
  * given board.
  *
  * Legality is determined entirely by MoveApplier so that there is one source of truth for move
  * validation.
  */
object GameStatusEvaluator:

  def evaluate(
      board: Board,
      currentPlayer: Color,
      castlingRights: CastlingRights = CastlingRights.none,
      enPassantState: Option[EnPassantState] = None
  ): GameStatus =
    val inCheck = CheckValidator.isKingInCheck(board, currentPlayer)
    val hasMove = hasAnyLegalMove(board, currentPlayer, castlingRights, enPassantState)
    (inCheck, hasMove) match
      case (false, true)  => GameStatus.Ongoing(false)
      case (true, true)   => GameStatus.Ongoing(true)
      case (true, false)  => GameStatus.Checkmate(currentPlayer.opposite)
      case (false, false) => GameStatus.Draw(DrawReason.Stalemate)

  def evaluate(state: GameState): GameStatus =
    evaluate(state.board, state.currentPlayer, state.castlingRights, state.enPassantState)

  /** True if `color` has at least one legal move (including castling and en passant).
    * Short-circuits on the first success.
    */
  def hasAnyLegalMove(
      board: Board,
      color: Color,
      castlingRights: CastlingRights = CastlingRights.none,
      enPassantState: Option[EnPassantState] = None
  ): Boolean =
    allPieces(board, color).exists { case (from, _) =>
      allSquares.exists { to =>
        MoveApplier.applyMove(board, Move(from, to), castlingRights, enPassantState).isRight
      }
    }

  // ── helpers ────────────────────────────────────────────────────────────────

  /** All 64 valid positions, computed once. */
  private lazy val allSquares: Seq[Position] =
    for
      f <- 0 to 7
      r <- 0 to 7
      pos <- Position.from(f, r).toOption.toSeq
    yield pos

  private def allPieces(board: Board, color: Color): Iterator[(Position, Piece)] =
    board.piecesIterator.filter(_._2.color == color)
