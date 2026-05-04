package chess.adapter.rest.contract.dto

/** Statistics for a single square on the board in the heatmap.
  *
  * @param square
  *   board coordinate (e.g., "e4")
  * @param captureCount
  *   number of times the player captured an opponent's piece on this square
  * @param occupancyCount
  *   number of times the player had a piece on this square
  * @param normalizedCaptureScore
  *   capture intensity normalized to 0.0-1.0 for visualization (0=no captures, 1=max captures)
  * @param normalizedOccupancyScore
  *   occupancy intensity normalized to 0.0-1.0 for visualization (0=never visited, 1=most visited)
  */
final case class HeatmapSquareStats(
    square: String,
    captureCount: Int,
    occupancyCount: Int,
    normalizedCaptureScore: Double,
    normalizedOccupancyScore: Double
)

/** Heatmap visualization data for player statistics.
  *
  * Shows move patterns, capture intensity, and piece positioning frequency across the board. Data
  * is derived and pre-aggregated from game history for fast loading.
  *
  * @param playerColor
  *   "White" or "Black"
  * @param periodLabel
  *   human-readable label for the time period covered (e.g., "Last 10 games", "Last 7 days")
  * @param gamesAnalyzed
  *   number of games included in this aggregation
  * @param statistics
  *   array of 64 HeatmapSquareStats, one per square in rank-file order (a1, b1, c1, ... h8)
  * @param maxCaptureCount
  *   highest single capture count seen across all squares (for normalization reference)
  * @param maxOccupancyCount
  *   highest single occupancy count seen across all squares (for normalization reference)
  */
final case class HeatmapResponse(
    playerColor: String,
    periodLabel: String,
    gamesAnalyzed: Int,
    statistics: Vector[HeatmapSquareStats],
    maxCaptureCount: Int,
    maxOccupancyCount: Int
)
