package chess.application.stats

import chess.adapter.rest.contract.dto.{HeatmapResponse, HeatmapSquareStats}
import chess.state.GameState
import chess.model.Color

import scala.annotation.tailrec

/** Service for computing heatmap statistics from game history.
  *
  * Aggregates move data to produce visualizations of where pieces were captured and where they were
  * most active.
  */
object HeatmapService {

  /** Build heatmap statistics from a complete game state.
    *
    * Iterates through the move history and records:
    *   - Occupancy: each destination square visited
    *   - Captures: each square where an enemy piece was taken
    *
    * @param gameState
    *   the completed or ongoing game
    * @param playerColor
    *   "White" or "Black" - the player to analyze
    * @return
    *   HeatmapStats aggregating all moves by this player
    */
  def buildStatsFromGame(gameState: GameState, playerColor: String): HeatmapStats = {
    val moves = gameState.moveHistory

    @tailrec
    def loop(index: Int, stats: HeatmapStats, board: GameState): HeatmapStats = {
      if (index >= moves.length) {
        stats.withGameProcessed
      } else {
        // Determine whose move this is (alternates starting with White)
        val isWhiteMove = (index % 2) == 0
        val moveIsForPlayer =
          (isWhiteMove && playerColor == "White") || (!isWhiteMove && playerColor == "Black")

        if (moveIsForPlayer) {
          val move = moves(index)
          // Check if there's a piece being captured (simplified check)
          val isCapture = board.board.pieces.exists(p => p.position == move.to)
          val newStats = stats.recordMove(move, isCapture, board.board)
          loop(index + 1, newStats, board)
        } else {
          loop(index + 1, stats, board)
        }
      }
    }

    loop(0, HeatmapStats.empty(playerColor), gameState)
  }

  /** Convert HeatmapStats to the HTTP response DTO.
    *
    * @param stats
    *   internal aggregated statistics
    * @param periodLabel
    *   human-readable label for the time period (e.g., "Last 10 games")
    * @return
    *   HeatmapResponse ready to serialize to JSON
    */
  def toResponse(stats: HeatmapStats, periodLabel: String): HeatmapResponse = {
    val (normalizedCaptures, normalizedOccupancy) = HeatmapStats.normalizeScores(stats)

    val maxCaptures = stats.capturesBySquare.values.maxOption.getOrElse(0)
    val maxOccupancy = stats.occupancyBySquare.values.maxOption.getOrElse(0)

    val allSquares = generateAllSquares()
    val squareStats = allSquares.map { square =>
      HeatmapSquareStats(
        square = square,
        captureCount = stats.capturesBySquare.getOrElse(square, 0),
        occupancyCount = stats.occupancyBySquare.getOrElse(square, 0),
        normalizedCaptureScore = normalizedCaptures.getOrElse(square, 0.0),
        normalizedOccupancyScore = normalizedOccupancy.getOrElse(square, 0.0)
      )
    }

    HeatmapResponse(
      playerColor = stats.playerColor,
      periodLabel = periodLabel,
      gamesAnalyzed = stats.gamesProcessed,
      statistics = squareStats.toVector,
      maxCaptureCount = maxCaptures,
      maxOccupancyCount = maxOccupancy
    )
  }

  /** Generate all 64 board squares in rank-file order (a1, b1, c1, ..., h8). */
  private def generateAllSquares(): List[String] = {
    val files = 'a' to 'h'
    val ranks = 1 to 8
    (for {
      rank <- ranks
      file <- files
    } yield s"$file$rank").toList
  }
}
