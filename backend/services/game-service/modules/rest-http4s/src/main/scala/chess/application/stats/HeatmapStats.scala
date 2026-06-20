package chess.application.stats

import chess.domain.model.Move

/** Aggregated heatmap statistics for a player across one game. */
final case class HeatmapStats(
    playerColor: String,
    capturesBySquare: Map[String, Int] = Map.empty,
    occupancyBySquare: Map[String, Int] = Map.empty,
    gamesProcessed: Int = 0
):
  def recordMove(move: Move, isCapture: Boolean): HeatmapStats =
    val toSquare = move.to.toString
    val nextOccupancy =
      occupancyBySquare.updated(toSquare, occupancyBySquare.getOrElse(toSquare, 0) + 1)
    val nextCaptures =
      if isCapture then
        capturesBySquare.updated(toSquare, capturesBySquare.getOrElse(toSquare, 0) + 1)
      else capturesBySquare

    copy(capturesBySquare = nextCaptures, occupancyBySquare = nextOccupancy)

  def withGameProcessed: HeatmapStats = copy(gamesProcessed = gamesProcessed + 1)

object HeatmapStats:
  def empty(playerColor: String): HeatmapStats = HeatmapStats(playerColor = playerColor)

  def normalizeScores(stats: HeatmapStats): (Map[String, Double], Map[String, Double]) =
    val maxCapture = stats.capturesBySquare.values.maxOption.getOrElse(0).toDouble
    val maxOccupancy = stats.occupancyBySquare.values.maxOption.getOrElse(0).toDouble

    val normalizedCaptures =
      if maxCapture > 0 then stats.capturesBySquare.view.mapValues(_ / maxCapture).toMap
      else Map.empty[String, Double]

    val normalizedOccupancy =
      if maxOccupancy > 0 then stats.occupancyBySquare.view.mapValues(_ / maxOccupancy).toMap
      else Map.empty[String, Double]

    (normalizedCaptures, normalizedOccupancy)
