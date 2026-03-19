package chess.domain.rules

import chess.domain.model.*

/** Evaluates the game status (ongoing / check / checkmate / stalemate)
 *  for the side to move on a given board.
 *
 *  Legality is determined entirely by MoveApplier so that there is one
 *  source of truth for move validation.
 */
object GameStatusEvaluator:

  def evaluate(board: Board, currentPlayer: Color): GameStatus =
    val inCheck = CheckValidator.isKingInCheck(board, currentPlayer)
    val hasMove = hasAnyLegalMove(board, currentPlayer)
    (inCheck, hasMove) match
      case (false, true)  => GameStatus.Ongoing
      case (true,  true)  => GameStatus.Check
      case (true,  false) => GameStatus.Checkmate
      case (false, false) => GameStatus.Stalemate

  /** True if `color` has at least one pseudo-legal move that passes full
   *  validation (including king-safety).  Short-circuits on the first success.
   */
  def hasAnyLegalMove(board: Board, color: Color): Boolean =
    allPieces(board, color).exists { case (from, _) =>
      allSquares.exists { to =>
        MoveApplier.applyMove(board, Move(from, to)).isRight
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

  private def allPieces(board: Board, color: Color): Seq[(Position, Piece)] =
    allSquares.flatMap { pos =>
      board.pieceAt(pos).filter(_.color == color).map(pos -> _)
    }
