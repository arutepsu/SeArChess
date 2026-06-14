package chess.analyticsservice.slick

import chess.analyticsservice.*
import _root_.slick.jdbc.GetResult
import _root_.slick.jdbc.PostgresProfile.api.*

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Read-only Slick repository over the analytics_* tables written by Spark batch analytics.
  *
  * Column names in those tables are camelCase (Spark JDBC preserves DataFrame column names
  * using quoted PostgreSQL identifiers). All queries use "double-quoted" column references.
  *
  * The "latest run" is resolved via MAX(created_at) on analytics_leaderboard, which is always
  * written — if that table is absent (Spark hasn't run yet), all methods return Left(message).
  *
  * Run-specific methods use Slick bind parameters ($runId) so the runId is never interpolated
  * as raw SQL. The schema name uses literal interpolation (#$t) and is validated by a regex
  * in AnalyticsServiceConfig before the repository is constructed.
  */
final class SlickAnalyticsRepository(db: Database, schema: String) extends AnalyticsRepository:

  private val t = schema  // table prefix alias

  // ── GetResult instances ────────────────────────────────────────────────────

  private given GetResult[AnalyticsRunSummary] = GetResult { r =>
    AnalyticsRunSummary(r.nextString(), r.nextString(), r.nextString())
  }

  private given GetResult[LeaderboardRow] = GetResult { r =>
    LeaderboardRow(r.nextString(), r.nextDouble(), r.nextLong(), r.nextLong(),
      r.nextLong(), r.nextLong(), r.nextDouble(), r.nextDouble())
  }

  private given GetResult[BotFamilyRow] = GetResult { r =>
    BotFamilyRow(r.nextString(), r.nextLong(), r.nextLong(), r.nextLong(),
      r.nextLong(), r.nextDouble(), r.nextDouble())
  }

  private given GetResult[StrategyRow] = GetResult { r =>
    StrategyRow(r.nextString(), r.nextLong(), r.nextLong(), r.nextLong(),
      r.nextLong(), r.nextDouble(), r.nextDouble())
  }

  private given GetResult[SearchessAiComparisonRow] = GetResult { r =>
    SearchessAiComparisonRow(r.nextString(), r.nextString(), r.nextLong(), r.nextLong(),
      r.nextLong(), r.nextLong(), r.nextDouble(), r.nextDouble(), r.nextDouble())
  }

  private given GetResult[StockfishComparisonRow] = GetResult { r =>
    StockfishComparisonRow(r.nextString(), r.nextString(), r.nextLong(), r.nextLong(),
      r.nextLong(), r.nextDouble(), r.nextDouble(), r.nextDouble())
  }

  private given GetResult[AvgGameLengthRow] = GetResult { r =>
    AvgGameLengthRow(r.nextString(), r.nextString(), r.nextLong(), r.nextDouble(), r.nextDouble())
  }

  private given GetResult[EloRatingsRow] = GetResult { r =>
    EloRatingsRow(
      r.nextString(), r.nextDouble(), r.nextDouble(), r.nextLong(), r.nextLong(),
      r.nextLong(), r.nextLong(), r.nextDouble(), r.nextString()
    )
  }

  private given GetResult[TerminationReasonRow] = GetResult { r =>
    TerminationReasonRow(r.nextString(), r.nextLong())
  }

  private given GetResult[ColorPerformanceRow] = GetResult { r =>
    ColorPerformanceRow(
      r.nextString(), r.nextLong(), r.nextLong(), r.nextDouble(),
      r.nextLong(), r.nextLong(), r.nextDouble()
    )
  }

  private given GetResult[FastestWinRow] = GetResult { r =>
    FastestWinRow(r.nextString(), r.nextLong(), r.nextDouble(), r.nextLong(), r.nextDouble())
  }

  // ── Latest-run convenience methods ────────────────────────────────────────

  override def listRuns(): Either[String, List[AnalyticsRunSummary]] =
    run(
      sql"""SELECT DISTINCT run_id, source_path, created_at
            FROM #$t.analytics_leaderboard
            ORDER BY created_at DESC""".as[AnalyticsRunSummary]
    )

  override def getLatestLeaderboard(): Either[String, List[LeaderboardRow]] =
    run(
      sql"""SELECT "botId", "totalScore", "wins", "draws", "losses", "gamesPlayed", "avgPly", "winRate"
            FROM #$t.analytics_leaderboard
            WHERE run_id = (SELECT run_id FROM #$t.analytics_leaderboard ORDER BY created_at DESC LIMIT 1)
            ORDER BY "totalScore" DESC""".as[LeaderboardRow]
    )

  override def getLatestBotFamilyComparison(): Either[String, List[BotFamilyRow]] =
    run(
      sql"""SELECT "family", "games", "wins", "losses", "draws", "totalScore", "winRate"
            FROM #$t.analytics_bot_family_comparison
            WHERE run_id = (SELECT run_id FROM #$t.analytics_leaderboard ORDER BY created_at DESC LIMIT 1)
            ORDER BY "totalScore" DESC""".as[BotFamilyRow]
    )

  override def getLatestStrategyComparison(): Either[String, List[StrategyRow]] =
    run(
      sql"""SELECT "strategyType", "games", "wins", "losses", "draws", "totalScore", "winRate"
            FROM #$t.analytics_strategy_comparison
            WHERE run_id = (SELECT run_id FROM #$t.analytics_leaderboard ORDER BY created_at DESC LIMIT 1)
            ORDER BY "totalScore" DESC""".as[StrategyRow]
    )

  override def getLatestSearchessAiComparison(): Either[String, List[SearchessAiComparisonRow]] =
    run(
      sql"""SELECT "opponentBotId", "opponentFamily", "games", "searchessAiWins", "draws", "losses", "score", "avgGameLength", "winRate"
            FROM #$t.analytics_searchess_ai_comparison
            WHERE run_id = (SELECT run_id FROM #$t.analytics_leaderboard ORDER BY created_at DESC LIMIT 1)
            ORDER BY "score" DESC""".as[SearchessAiComparisonRow]
    )

  override def getLatestStockfishComparison(): Either[String, List[StockfishComparisonRow]] =
    run(
      sql"""SELECT "botId", "strategyType", "games", "wins", "draws", "totalScore", "avgGameLength", "winRate"
            FROM #$t.analytics_stockfish_comparison
            WHERE run_id = (SELECT run_id FROM #$t.analytics_leaderboard ORDER BY created_at DESC LIMIT 1)
            ORDER BY "totalScore" DESC""".as[StockfishComparisonRow]
    )

  override def getLatestAvgGameLength(): Either[String, List[AvgGameLengthRow]] =
    run(
      sql"""SELECT "whiteBotId", "blackBotId", "gamesPlayed", "avgTotalPly", "avgDurationMs"
            FROM #$t.analytics_avg_game_length
            WHERE run_id = (SELECT run_id FROM #$t.analytics_leaderboard ORDER BY created_at DESC LIMIT 1)
            ORDER BY "whiteBotId", "blackBotId"""".as[AvgGameLengthRow]
    )

  override def getEloRatings(): Either[String, List[EloRatingsRow]] =
    run(
      sql"""SELECT "botId", "rating", "ratingChange", "gamesPlayed", "wins", "draws", "losses", "averageOpponentRating", "lastGameTimestamp"
            FROM #$t.analytics_elo_ratings
            WHERE run_id = (SELECT run_id FROM #$t.analytics_leaderboard ORDER BY created_at DESC LIMIT 1)
            ORDER BY "rating" DESC""".as[EloRatingsRow]
    )

  override def getTerminations(): Either[String, List[TerminationReasonRow]] =
    run(
      sql"""SELECT "terminationReason", "count"
            FROM #$t.analytics_terminations
            WHERE run_id = (SELECT run_id FROM #$t.analytics_leaderboard ORDER BY created_at DESC LIMIT 1)
            ORDER BY "count" DESC""".as[TerminationReasonRow]
    )

  override def getColorPerformance(): Either[String, List[ColorPerformanceRow]] =
    run(
      sql"""SELECT "botId", "gamesAsWhite", "whiteWins", "whiteScore", "gamesAsBlack", "blackWins", "blackScore"
            FROM #$t.analytics_color_performance
            WHERE run_id = (SELECT run_id FROM #$t.analytics_leaderboard ORDER BY created_at DESC LIMIT 1)
            ORDER BY "botId" ASC""".as[ColorPerformanceRow]
    )

  override def getFastestWins(): Either[String, List[FastestWinRow]] =
    run(
      sql"""SELECT "winnerBotId", "decisiveGames", "avgWinPly", "minWinPly", "avgWinDurationMs"
            FROM #$t.analytics_fastest_wins
            WHERE run_id = (SELECT run_id FROM #$t.analytics_leaderboard ORDER BY created_at DESC LIMIT 1)
            ORDER BY "avgWinPly" ASC""".as[FastestWinRow]
    )

  // ── Run-specific methods ───────────────────────────────────────────────────

  override def getLeaderboard(runId: String): Either[String, List[LeaderboardRow]] =
    run(
      sql"""SELECT "botId", "totalScore", "wins", "draws", "losses", "gamesPlayed", "avgPly", "winRate"
            FROM #$t.analytics_leaderboard
            WHERE run_id = $runId
            ORDER BY "totalScore" DESC""".as[LeaderboardRow]
    )

  override def getBotFamilyComparison(runId: String): Either[String, List[BotFamilyRow]] =
    run(
      sql"""SELECT "family", "games", "wins", "losses", "draws", "totalScore", "winRate"
            FROM #$t.analytics_bot_family_comparison
            WHERE run_id = $runId
            ORDER BY "totalScore" DESC""".as[BotFamilyRow]
    )

  override def getStrategyComparison(runId: String): Either[String, List[StrategyRow]] =
    run(
      sql"""SELECT "strategyType", "games", "wins", "losses", "draws", "totalScore", "winRate"
            FROM #$t.analytics_strategy_comparison
            WHERE run_id = $runId
            ORDER BY "totalScore" DESC""".as[StrategyRow]
    )

  override def getSearchessAiComparison(runId: String): Either[String, List[SearchessAiComparisonRow]] =
    run(
      sql"""SELECT "opponentBotId", "opponentFamily", "games", "searchessAiWins", "draws", "losses", "score", "avgGameLength", "winRate"
            FROM #$t.analytics_searchess_ai_comparison
            WHERE run_id = $runId
            ORDER BY "score" DESC""".as[SearchessAiComparisonRow]
    )

  override def getStockfishComparison(runId: String): Either[String, List[StockfishComparisonRow]] =
    run(
      sql"""SELECT "botId", "strategyType", "games", "wins", "draws", "totalScore", "avgGameLength", "winRate"
            FROM #$t.analytics_stockfish_comparison
            WHERE run_id = $runId
            ORDER BY "totalScore" DESC""".as[StockfishComparisonRow]
    )

  override def getAvgGameLength(runId: String): Either[String, List[AvgGameLengthRow]] =
    run(
      sql"""SELECT "whiteBotId", "blackBotId", "gamesPlayed", "avgTotalPly", "avgDurationMs"
            FROM #$t.analytics_avg_game_length
            WHERE run_id = $runId
            ORDER BY "whiteBotId", "blackBotId"""".as[AvgGameLengthRow]
    )

  override def getEloRatings(runId: String): Either[String, List[EloRatingsRow]] =
    run(
      sql"""SELECT "botId", "rating", "ratingChange", "gamesPlayed", "wins", "draws", "losses", "averageOpponentRating", "lastGameTimestamp"
            FROM #$t.analytics_elo_ratings
            WHERE run_id = $runId
            ORDER BY "rating" DESC""".as[EloRatingsRow]
    )

  override def getTerminations(runId: String): Either[String, List[TerminationReasonRow]] =
    run(
      sql"""SELECT "terminationReason", "count"
            FROM #$t.analytics_terminations
            WHERE run_id = $runId
            ORDER BY "count" DESC""".as[TerminationReasonRow]
    )

  override def getColorPerformance(runId: String): Either[String, List[ColorPerformanceRow]] =
    run(
      sql"""SELECT "botId", "gamesAsWhite", "whiteWins", "whiteScore", "gamesAsBlack", "blackWins", "blackScore"
            FROM #$t.analytics_color_performance
            WHERE run_id = $runId
            ORDER BY "botId" ASC""".as[ColorPerformanceRow]
    )

  override def getFastestWins(runId: String): Either[String, List[FastestWinRow]] =
    run(
      sql"""SELECT "winnerBotId", "decisiveGames", "avgWinPly", "minWinPly", "avgWinDurationMs"
            FROM #$t.analytics_fastest_wins
            WHERE run_id = $runId
            ORDER BY "avgWinPly" ASC""".as[FastestWinRow]
    )

  // ── Internal helpers ──────────────────────────────────────────────────────

  private def run[T](action: DBIOAction[Vector[T], NoStream, Effect.Read]): Either[String, List[T]] =
    try Right(Await.result(db.run(action), 30.seconds).toList)
    catch case NonFatal(e) =>
      val msg = Option(e.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)
      Left(s"Analytics query failed: $msg")
