package chess.application.stats

import chess.adapter.rest.contract.dto.{HeatmapResponse, HeatmapSquareStats}
import chess.domain.model.Move
import chess.domain.rules.GameStateRules
import chess.domain.state.{GameState, GameStateFactory}

import scala.annotation.tailrec

/** Service for computing heatmap statistics from a game state. */
object HeatmapService:

  def buildStatsFromGame(gameState: GameState, playerColor: String): HeatmapStats =
    @tailrec
    def loop(state: GameState, remainingMoves: List[Move], stats: HeatmapStats): HeatmapStats =
      remainingMoves match
        case Nil => stats.withGameProcessed
        case move :: tail =>
          val moveOwner = state.currentPlayer.toString
          val isRelevantMove = moveOwner == playerColor
          val isCapture = capturesPiece(state, move)

          val nextStats =
            if isRelevantMove then stats.recordMove(move, isCapture)
            else stats

          GameStateRules.applyMove(state, move) match
            case Right(nextState) => loop(nextState, tail, nextStats)
            case Left(_)          => nextStats.withGameProcessed

    loop(GameStateFactory.initial(), gameState.moveHistory, HeatmapStats.empty(playerColor))

  def toResponse(stats: HeatmapStats, periodLabel: String): HeatmapResponse =
    val (normalizedCaptures, normalizedOccupancy) = HeatmapStats.normalizeScores(stats)
    val maxCaptures = stats.capturesBySquare.values.maxOption.getOrElse(0)
    val maxOccupancy = stats.occupancyBySquare.values.maxOption.getOrElse(0)
    val statistics = generateAllSquares().map { square =>
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
      statistics = statistics.toVector,
      maxCaptureCount = maxCaptures,
      maxOccupancyCount = maxOccupancy
    )

  private def capturesPiece(state: GameState, move: Move): Boolean =
    state.board.pieceAt(move.to).isDefined || enPassantCapture(state, move)

  private def enPassantCapture(state: GameState, move: Move): Boolean =
    state.enPassantState.exists(_.targetSquare == move.to)

  private def generateAllSquares(): List[String] =
    val files = 'a' to 'h'
    val ranks = 1 to 8
    (for
      rank <- ranks
      file <- files
    yield s"$file$rank").toList
