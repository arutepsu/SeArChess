package chess.application.stats

import chess.model.{Board, Move, Piece, PieceType}
import scala.annotation.tailrec

/** Aggregated heatmap statistics for a player across games.
  *
  * Tracks:
  *   - Where pieces were captured
  *   - Which squares were visited most often
  */
final case class HeatmapStats(
    playerColor: String,
    capturesBySquare: Map[String, Int] = Map.empty,
    occupancyBySquare: Map[String, Int] = Map.empty,
    gamesProcessed: Int = 0
) {

  /** Add statistics from a single game move.
    *
    * Records occupancy for the destination square of the move. Records a capture if a piece was
    * taken.
    */
  def recordMove(move: Move, isCapture: Boolean, fromBoard: Board): HeatmapStats = {
    val toSquare = s"${move.to.file}${move.to.rank}"
    val newOccupancy =
      occupancyBySquare.updated(toSquare, occupancyBySquare.getOrElse(toSquare, 0) + 1)

    val newCaptures = if (isCapture) {
      capturesBySquare.updated(toSquare, capturesBySquare.getOrElse(toSquare, 0) + 1)
    } else {
      capturesBySquare
    }

    this.copy(
      capturesBySquare = newCaptures,
      occupancyBySquare = newOccupancy
    )
  }

  /** Mark a game as processed for aggregation context. */
  def withGameProcessed: HeatmapStats = this.copy(gamesProcessed = gamesProcessed + 1)

  /** Merge statistics from another heatmap. */
  def merge(other: HeatmapStats): HeatmapStats = {
    HeatmapStats(
      playerColor = playerColor,
      capturesBySquare = mergeCountMaps(capturesBySquare, other.capturesBySquare),
      occupancyBySquare = mergeCountMaps(occupancyBySquare, other.occupancyBySquare),
      gamesProcessed = gamesProcessed + other.gamesProcessed
    )
  }

  private def mergeCountMaps(m1: Map[String, Int], m2: Map[String, Int]): Map[String, Int] = {
    (m1.keySet ++ m2.keySet).foldLeft(Map.empty[String, Int]) { (acc, key) =>
      val v1 = m1.getOrElse(key, 0)
      val v2 = m2.getOrElse(key, 0)
      acc.updated(key, v1 + v2)
    }
  }
}

object HeatmapStats {
  def empty(playerColor: String): HeatmapStats = HeatmapStats(playerColor = playerColor)

  /** Generate normalized scores (0.0-1.0) for visualization. Prevents division by zero.
    */
  def normalizeScores(stats: HeatmapStats): (Map[String, Double], Map[String, Double]) = {
    val maxCapture = stats.capturesBySquare.values.maxOption.getOrElse(0).toDouble
    val maxOccupancy = stats.occupancyBySquare.values.maxOption.getOrElse(0).toDouble

    val normalizedCaptures = if (maxCapture > 0) {
      stats.capturesBySquare.view.mapValues(_ / maxCapture).toMap
    } else {
      Map.empty[String, Double]
    }

    val normalizedOccupancy = if (maxOccupancy > 0) {
      stats.occupancyBySquare.view.mapValues(_ / maxOccupancy).toMap
    } else {
      Map.empty[String, Double]
    }

    (normalizedCaptures, normalizedOccupancy)
  }
}
