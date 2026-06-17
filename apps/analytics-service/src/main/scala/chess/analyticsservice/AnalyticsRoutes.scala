package chess.analyticsservice

import cats.effect.IO
import cats.syntax.semigroupk.*
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

class AnalyticsRoutes(repo: AnalyticsRepository):

  private object RawLimitParam extends OptionalQueryParamDecoderMatcher[String]("limit")

  private def parseLimitParam(raw: Option[String]): Either[String, Int] =
    raw match
      case None    => Right(50)
      case Some(s) =>
        s.toIntOption match
          case None    => Left(s"limit must be a positive integer, got: '$s'")
          case Some(n) => Right(n.max(1).min(200))

  // UUID format: 8-4-4-4-12 hex digits
  private val uuidPattern = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}".r

  private def validateRunId(runId: String): Either[String, String] =
    if uuidPattern.matches(runId) then Right(runId)
    else Left(s"Invalid run ID: expected UUID format (xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx), got '$runId'")

  val operationalRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root / "health" =>
    json(Status.Ok, ujson.Obj("status" -> "ok", "service" -> "searchess-analytics-service"))
  }

  val analyticsRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    // ── Runs listing ────────────────────────────────────────────────────────
    case GET -> Root / "api" / "analytics" / "runs" =>
      repo.listRuns() match
        case Right(runs) =>
          json(Status.Ok, ujson.Obj("runs" -> ujson.Arr(runs.map(AnalyticsRowJson.runSummary)*)))
        case Left(msg) =>
          error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    // ── Latest-run endpoints ────────────────────────────────────────────────
    case GET -> Root / "api" / "analytics" / "latest" / "leaderboard" =>
      repo.getLatestLeaderboard() match
        case Right(rows) =>
          json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.leaderboard)*)))
        case Left(msg) =>
          error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "latest" / "bot-families" =>
      repo.getLatestBotFamilyComparison() match
        case Right(rows) =>
          json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.botFamily)*)))
        case Left(msg) =>
          error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "latest" / "strategies" =>
      repo.getLatestStrategyComparison() match
        case Right(rows) =>
          json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.strategy)*)))
        case Left(msg) =>
          error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "latest" / "searchess-ai" =>
      repo.getLatestSearchessAiComparison() match
        case Right(rows) =>
          json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.searchessAi)*)))
        case Left(msg) =>
          error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "latest" / "stockfish" =>
      repo.getLatestStockfishComparison() match
        case Right(rows) =>
          json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.stockfish)*)))
        case Left(msg) =>
          error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "latest" / "avg-game-length" =>
      repo.getLatestAvgGameLength() match
        case Right(rows) =>
          json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.avgGameLength)*)))
        case Left(msg) =>
          error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "latest" / "elo-ratings" =>
      repo.getEloRatings() match
        case Right(rows) =>
          json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.eloRatings)*)))
        case Left(msg) =>
          error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "latest" / "terminations" =>
      repo.getTerminations() match
        case Right(rows) =>
          json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.terminationReason)*)))
        case Left(msg) =>
          error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "latest" / "color-performance" =>
      repo.getColorPerformance() match
        case Right(rows) =>
          json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.colorPerformance)*)))
        case Left(msg) =>
          error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "latest" / "fastest-wins" =>
      repo.getFastestWins() match
        case Right(rows) =>
          json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.fastestWin)*)))
        case Left(msg) =>
          error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    // ── Run-specific endpoints ──────────────────────────────────────────────
    case GET -> Root / "api" / "analytics" / "runs" / runId / "leaderboard" =>
      validateRunId(runId) match
        case Left(msg) => error(Status.BadRequest, "INVALID_RUN_ID", msg)
        case Right(id) =>
          repo.getLeaderboard(id) match
            case Right(rows) =>
              json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.leaderboard)*)))
            case Left(msg) =>
              error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "runs" / runId / "bot-families" =>
      validateRunId(runId) match
        case Left(msg) => error(Status.BadRequest, "INVALID_RUN_ID", msg)
        case Right(id) =>
          repo.getBotFamilyComparison(id) match
            case Right(rows) =>
              json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.botFamily)*)))
            case Left(msg) =>
              error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "runs" / runId / "strategies" =>
      validateRunId(runId) match
        case Left(msg) => error(Status.BadRequest, "INVALID_RUN_ID", msg)
        case Right(id) =>
          repo.getStrategyComparison(id) match
            case Right(rows) =>
              json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.strategy)*)))
            case Left(msg) =>
              error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "runs" / runId / "searchess-ai" =>
      validateRunId(runId) match
        case Left(msg) => error(Status.BadRequest, "INVALID_RUN_ID", msg)
        case Right(id) =>
          repo.getSearchessAiComparison(id) match
            case Right(rows) =>
              json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.searchessAi)*)))
            case Left(msg) =>
              error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "runs" / runId / "stockfish" =>
      validateRunId(runId) match
        case Left(msg) => error(Status.BadRequest, "INVALID_RUN_ID", msg)
        case Right(id) =>
          repo.getStockfishComparison(id) match
            case Right(rows) =>
              json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.stockfish)*)))
            case Left(msg) =>
              error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "runs" / runId / "avg-game-length" =>
      validateRunId(runId) match
        case Left(msg) => error(Status.BadRequest, "INVALID_RUN_ID", msg)
        case Right(id) =>
          repo.getAvgGameLength(id) match
            case Right(rows) =>
              json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.avgGameLength)*)))
            case Left(msg) =>
              error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "runs" / runId / "elo-ratings" =>
      validateRunId(runId) match
        case Left(msg) => error(Status.BadRequest, "INVALID_RUN_ID", msg)
        case Right(id) =>
          repo.getEloRatings(id) match
            case Right(rows) =>
              json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.eloRatings)*)))
            case Left(msg) =>
              error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "runs" / runId / "terminations" =>
      validateRunId(runId) match
        case Left(msg) => error(Status.BadRequest, "INVALID_RUN_ID", msg)
        case Right(id) =>
          repo.getTerminations(id) match
            case Right(rows) =>
              json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.terminationReason)*)))
            case Left(msg) =>
              error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "runs" / runId / "color-performance" =>
      validateRunId(runId) match
        case Left(msg) => error(Status.BadRequest, "INVALID_RUN_ID", msg)
        case Right(id) =>
          repo.getColorPerformance(id) match
            case Right(rows) =>
              json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.colorPerformance)*)))
            case Left(msg) =>
              error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "runs" / runId / "fastest-wins" =>
      validateRunId(runId) match
        case Left(msg) => error(Status.BadRequest, "INVALID_RUN_ID", msg)
        case Right(id) =>
          repo.getFastestWins(id) match
            case Right(rows) =>
              json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.fastestWin)*)))
            case Left(msg) =>
              error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    // ── Live streaming results ──────────────────────────────────────────────
    case GET -> Root / "api" / "analytics" / "live" / "game-results" :? RawLimitParam(rawLimit) =>
      parseLimitParam(rawLimit) match
        case Left(errMsg) => error(Status.BadRequest, "INVALID_LIMIT", errMsg)
        case Right(limit) =>
          repo.getLiveGameResults(limit) match
            case Right(rows) =>
              json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.liveGameResult)*)))
            case Left(msg) =>
              error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)
  }

  val routes: HttpRoutes[IO] = operationalRoutes <+> analyticsRoutes

  private def json(status: Status, body: ujson.Value): IO[Response[IO]] =
    IO.pure(
      Response[IO](
        status  = status,
        headers = Headers(`Content-Type`(MediaType.application.json)),
        body    = Stream.emits(ujson.write(body).getBytes("UTF-8")).covary[IO]
      )
    )

  private def error(status: Status, code: String, message: String): IO[Response[IO]] =
    json(status, ujson.Obj("code" -> code, "message" -> message))
