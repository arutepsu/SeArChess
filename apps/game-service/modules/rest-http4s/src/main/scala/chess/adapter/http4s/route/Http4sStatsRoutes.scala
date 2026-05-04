package chess.adapter.http4s.route

import cats.effect.IO
import chess.adapter.http4s.route.Http4sRouteSupport.*
import chess.adapter.rest.contract.dto.HeatmapResponse
import chess.application.GameServiceApi
import chess.application.port.repository.RepositoryError
import chess.application.session.model.SessionIds.GameId
import chess.application.stats.HeatmapService
import org.http4s.*
import org.http4s.dsl.io.*
import ujson.*

/** http4s routes for the `/stats` resource.
  *
  * Routes:
  *   - `GET /stats/heatmap` → [[handleGetHeatmap]] (query — heatmap statistics)
  *
  * Query parameters:
  *   - `sessionId` (required): UUID of the session to analyze
  *   - `player` (required): "White" or "Black"
  *   - `period` (optional): "all" (default), "recent", or a specific time period
  *
  * This endpoint aggregates move data from a game session and returns normalized heatmap data
  * (capture intensity and occupancy frequency per square) for visualization.
  */
class Http4sStatsRoutes(gameService: GameServiceApi):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "stats" / "heatmap" :? playerMatcher(player) +& sessionIdMatcher(
          sessionId
        ) =>
      handleGetHeatmap(sessionId, player)
  }

  // ── query parameter extractors ─────────────────────────────────────────────

  /** Extract `sessionId` query parameter. */
  private object sessionIdMatcher extends QueryParamDecoderMatcher[String]("sessionId")

  /** Extract `player` query parameter (validate as "White" or "Black"). */
  private object playerMatcher extends QueryParamDecoderMatcher[String]("player")

  // ── handlers ───────────────────────────────────────────────────────────────

  private def handleGetHeatmap(sessionIdStr: String, playerStr: String): IO[Response[IO]] = {
    val validatedPlayer = playerStr.toLowerCase match {
      case "white" => Right("White")
      case "black" => Right("Black")
      case _       => Left("Invalid player: must be 'White' or 'Black'")
    }

    val result = for {
      uuid <- parseUUID(sessionIdStr)
      player <- validatedPlayer
      gameView <- gameService.getGame(GameId(uuid)).left.map {
        case RepositoryError.NotFound(_)         => s"Game not found: $sessionIdStr"
        case RepositoryError.Conflict(msg)       => msg
        case RepositoryError.StorageFailure(msg) => msg
      }
    } yield {
      val stats = HeatmapService.buildStatsFromGame(gameView.toGameState, player)
      val periodLabel = "Current game"
      val response = HeatmapService.toResponse(stats, periodLabel)
      response
    }

    result match {
      case Left(msg) if msg.startsWith("Invalid UUID") || msg.contains("Invalid player") =>
        jsonError(Status.BadRequest, "INVALID_INPUT", msg)
      case Left(msg) if msg.startsWith("Game not found") =>
        jsonError(Status.NotFound, "GAME_NOT_FOUND", msg)
      case Left(msg) =>
        jsonError(Status.InternalServerError, "INTERNAL_ERROR", msg)
      case Right(response) =>
        jsonResponse(Status.Ok, toHeatmapJson(response))
    }
  }

  // ── JSON serialization ─────────────────────────────────────────────────────

  private def toHeatmapJson(response: HeatmapResponse): Value = {
    ujson.Obj(
      "playerColor" -> response.playerColor,
      "periodLabel" -> response.periodLabel,
      "gamesAnalyzed" -> response.gamesAnalyzed,
      "maxCaptureCount" -> response.maxCaptureCount,
      "maxOccupancyCount" -> response.maxOccupancyCount,
      "statistics" -> ujson.Arr(
        response.statistics.map { stat =>
          ujson.Obj(
            "square" -> stat.square,
            "captureCount" -> stat.captureCount,
            "occupancyCount" -> stat.occupancyCount,
            "normalizedCaptureScore" -> stat.normalizedCaptureScore,
            "normalizedOccupancyScore" -> stat.normalizedOccupancyScore
          )
        }*
      )
    )
  }
