package chess.adapter.textui

import chess.domain.model.{Color, GameState, Move, Position}
import scala.util.Random

/** A simple local AI player for standalone TUI use.
  *
  * Strategy: Prioritize capturing opponent pieces, then prefer moves that control the center, then
  * fall back to random legal moves.
  *
  * This is NOT a strong chess engine. It's intended only as a placeholder so that HumanVsAI mode
  * works in the standalone TUI without requiring external service integration.
  */
object LocalAiPlayer:

  /** Select a move for the given color in the current game state.
    *
    * Strategy:
    *   1. Prefer moves that capture opponent pieces 2. Prefer moves to center squares (d4, e4, d5,
    *      e5, etc.) 3. Otherwise, pick randomly
    */
  def selectMove(state: GameState, aiColor: Color): Option[Move] =
    val legalMoves = allLegalMoves(state, aiColor)

    if legalMoves.isEmpty then None
    else
      // Prefer capturing moves
      val capturingMoves = legalMoves.filter(move => state.board.pieceAt(move.to).isDefined)

      if capturingMoves.nonEmpty then
        // Pick the capture that takes the most valuable piece (if possible)
        Some(selectBestCapture(state, capturingMoves, aiColor))
      else
        // No captures available; prefer center control
        val centerSquares = Set(
          Position.from(3, 3), // d4
          Position.from(4, 3), // e4
          Position.from(3, 4), // d5
          Position.from(4, 4) // e5
        ).flatten

        val centerMoves = legalMoves.filter(move => centerSquares.contains(move.to))

        if centerMoves.nonEmpty then Some(centerMoves(Random.nextInt(centerMoves.size)))
        else Some(legalMoves(Random.nextInt(legalMoves.size)))

  private def allLegalMoves(state: GameState, color: Color): Vector[Move] =
    (for
      rank <- 0 to 7
      file <- 0 to 7
      from <- Position.from(file, rank).toOption
      piece <- state.board.pieceAt(from)
      if piece.color == color
      to <- state.possibleMovesFrom(from).toOption if to.nonEmpty
      move <- to
    yield move).toVector

  private def selectBestCapture(
      state: GameState,
      captures: Vector[Move],
      aiColor: Color
  ): Move =
    val captureValues = captures.map { move =>
      val capturedPiece = state.board.pieceAt(move.to)
      val value = capturedPiece.map(pieceValue).getOrElse(0)
      (move, value)
    }
    captureValues.maxBy(_._2)._1

  private def pieceValue(piece: chess.domain.model.Piece): Int =
    piece.pieceType match
      case chess.domain.model.PieceType.Queen  => 9
      case chess.domain.model.PieceType.Rook   => 5
      case chess.domain.model.PieceType.Bishop => 3
      case chess.domain.model.PieceType.Knight => 3
      case chess.domain.model.PieceType.Pawn   => 1
      case chess.domain.model.PieceType.King   => 0
