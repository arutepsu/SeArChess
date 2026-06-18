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

    case GET -> Root / "api" / "analytics" / "runs" / runId / "head-to-head" =>
      validateRunId(runId) match
        case Left(msg) => error(Status.BadRequest, "INVALID_RUN_ID", msg)
        case Right(id) =>
          repo.getHeadToHead(id) match
            case Right(rows) =>
              json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.headToHead)*)))
            case Left(msg) =>
              error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "runs" / runId / "family-matchups" =>
      validateRunId(runId) match
        case Left(msg) => error(Status.BadRequest, "INVALID_RUN_ID", msg)
        case Right(id) =>
          repo.getFamilyMatchups(id) match
            case Right(rows) =>
              json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.familyMatchup)*)))
            case Left(msg) =>
              error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "runs" / runId / "game-results" =>
      validateRunId(runId) match
        case Left(msg) => error(Status.BadRequest, "INVALID_RUN_ID", msg)
        case Right(id) =>
          repo.getGameResults(id) match
            case Right(rows) =>
              json(Status.Ok, ujson.Obj("rows" -> ujson.Arr(rows.map(AnalyticsRowJson.gameResult)*)))
            case Left(msg) =>
              error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

    case GET -> Root / "api" / "analytics" / "runs" / runId / "public-tournament" =>
      validateRunId(runId) match
        case Left(msg) => error(Status.BadRequest, "INVALID_RUN_ID", msg)
        case Right(id) =>
          buildPublicTournamentBundle(id) match
            case Right(bundle) => json(Status.Ok, bundle)
            case Left(msg)     => error(Status.ServiceUnavailable, "ANALYTICS_UNAVAILABLE", msg)

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

  private def buildPublicTournamentBundle(runId: String): Either[String, ujson.Value] =
    for
      lb       <- repo.getLeaderboard(runId)
      elo      <- repo.getEloRatings(runId)
      h2h      <- repo.getHeadToHead(runId)
      avgLen   <- repo.getAvgGameLength(runId)
      terms    <- repo.getTerminations(runId)
      colorP   <- repo.getColorPerformance(runId)
      fastest  <- repo.getFastestWins(runId)
      games    <- repo.getGameResults(runId)
    yield
      val families   = repo.getBotFamilyComparison(runId).getOrElse(Nil)
      val strategies = repo.getStrategyComparison(runId).getOrElse(Nil)

      val totalGames   = terms.map(_.count).sum
      val totalDecisive = lb.map(_.wins).sum
      val totalDraws   = lb.map(_.draws).sum / 2
      val botCount     = lb.size
      val winner       = lb.headOption.map(_.botId).getOrElse("")
      val avgPly       =
        if lb.isEmpty then 0.0
        else
          val weightedSum = lb.map(r => r.avgPly * r.gamesPlayed).sum
          val totalPlayed = lb.map(_.gamesPlayed).sum
          if totalPlayed == 0 then 0.0
          else math.round(weightedSum / totalPlayed * 10.0) / 10.0

      val medals = lb.zipWithIndex.take(3).map { case (r, i) =>
        val medal = i match
          case 0 => "gold"
          case 1 => "silver"
          case _ => "bronze"
        ujson.Obj("medal" -> medal, "botId" -> r.botId, "totalScore" -> r.totalScore)
      }

      val botStats = lb.zipWithIndex.map { case (r, i) =>
        ujson.Obj(
          "rank"        -> (i + 1).toDouble,
          "botId"       -> r.botId,
          "games"       -> r.gamesPlayed.toDouble,
          "wins"        -> r.wins.toDouble,
          "draws"       -> r.draws.toDouble,
          "losses"      -> r.losses.toDouble,
          "winRate"     -> r.winRate,
          "score"       -> r.totalScore,
          "avgPly"      -> r.avgPly
        )
      }

      ujson.Obj(
        "summary" -> ujson.Obj(
          "totalGames"    -> totalGames.toDouble,
          "botCount"      -> botCount.toDouble,
          "winner"        -> winner,
          "avgPly"        -> avgPly,
          "decisiveGames" -> totalDecisive.toDouble,
          "drawGames"     -> totalDraws.toDouble
        ),
        "botStatistics" -> ujson.Arr(botStats*),
        "leaderboard"   -> ujson.Arr(lb.map(AnalyticsRowJson.leaderboard)*),
        "eloRatings"    -> ujson.Arr(elo.map(AnalyticsRowJson.eloRatings)*),
        "games"         -> ujson.Arr(games.map(AnalyticsRowJson.gameResult)*),
        "matchups" -> ujson.Obj(
          "headToHead"           -> ujson.Arr(h2h.map(AnalyticsRowJson.headToHead)*),
          "avgGameLengthByPairing" -> ujson.Arr(avgLen.map(AnalyticsRowJson.avgGameLength)*)
        ),
        "behavior" -> ujson.Obj(
          "terminations"    -> ujson.Arr(terms.map(AnalyticsRowJson.terminationReason)*),
          "colorPerformance" -> ujson.Arr(colorP.map(AnalyticsRowJson.colorPerformance)*),
          "fastestWins"     -> ujson.Arr(fastest.map(AnalyticsRowJson.fastestWin)*)
        ),
        "metadata" -> ujson.Obj(
          "botFamilies"    -> ujson.Arr(families.map(AnalyticsRowJson.botFamily)*),
          "strategies"     -> ujson.Arr(strategies.map(AnalyticsRowJson.strategy)*),
          "engineVersions" -> ujson.Arr()
        ),
        "medals" -> ujson.Arr(medals*)
      )

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
