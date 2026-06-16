package chess.analytics.pipeline

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import java.util.UUID
import chess.analytics.ingestion.{BronzeEvents, SilverEvents}
import chess.analytics.config.{PostgresConfig, SparkLakeConfig}
import chess.analytics.sink.{ParquetLakeWriter, PostgresWriter}

case class AnalyticsRunResult(runId: String, sourcePath: String, outputPath: String)

object GameAnalytics {

  def loadGameFinished(spark: SparkSession, path: String): DataFrame =
    SilverEvents.gameFinished(BronzeEvents.loadEvents(spark, path))

  def computeLeaderboard(gameFinished: DataFrame): DataFrame = {
    val whiteView = gameFinished.select(
      col("whiteBotId").as("botId"),
      when(col("result") === "white", 1.0).when(col("result") === "draw", 0.5).otherwise(0.0).as("score"),
      when(col("result") === "white", 1).otherwise(0).as("wins"),
      when(col("result") === "draw",  1).otherwise(0).as("draws"),
      when(col("result") === "black", 1).otherwise(0).as("losses"),
      col("totalPly")
    )

    val blackView = gameFinished.select(
      col("blackBotId").as("botId"),
      when(col("result") === "black", 1.0).when(col("result") === "draw", 0.5).otherwise(0.0).as("score"),
      when(col("result") === "black", 1).otherwise(0).as("wins"),
      when(col("result") === "draw",  1).otherwise(0).as("draws"),
      when(col("result") === "white", 1).otherwise(0).as("losses"),
      col("totalPly")
    )

    whiteView.union(blackView)
      .groupBy("botId")
      .agg(
        sum("score").as("totalScore"),
        sum("wins").as("wins"),
        sum("draws").as("draws"),
        sum("losses").as("losses"),
        count("botId").as("gamesPlayed"),
        round(avg("totalPly"), 1).as("avgPly")
      )
      .withColumn("winRate", round(col("wins").cast("double") / col("gamesPlayed"), 3))
      .orderBy(desc("totalScore"), desc("wins"))
  }

  def computeHeadToHead(gameFinished: DataFrame): DataFrame =
    gameFinished
      .groupBy("whiteBotId", "blackBotId")
      .agg(
        count("*").as("gamesPlayed"),
        sum(when(col("result") === "white", 1).otherwise(0)).as("whiteWins"),
        sum(when(col("result") === "black", 1).otherwise(0)).as("blackWins"),
        sum(when(col("result") === "draw",  1).otherwise(0)).as("draws"),
        sum(when(col("result") === "white", 1.0).when(col("result") === "draw", 0.5).otherwise(0.0)).as("whiteScore"),
        sum(when(col("result") === "black", 1.0).when(col("result") === "draw", 0.5).otherwise(0.0)).as("blackScore")
      )
      .orderBy("whiteBotId", "blackBotId")

  def computeTerminations(gameFinished: DataFrame): DataFrame =
    gameFinished
      .groupBy("terminationReason")
      .agg(count("*").as("count"))
      .orderBy(desc("count"))

  def computeAvgGameLength(gameFinished: DataFrame): DataFrame =
    gameFinished
      .groupBy("whiteBotId", "blackBotId")
      .agg(
        count("*").as("gamesPlayed"),
        round(avg("totalPly"), 1).as("avgTotalPly"),
        round(avg("durationMillis"), 0).as("avgDurationMs")
      )
      .orderBy("whiteBotId", "blackBotId")

  def computeBotFamilyComparison(gameFinished: DataFrame): DataFrame = {
    val whiteView = gameFinished.select(
      col("whiteBotFamily").as("family"),
      when(col("result") === "white", 1.0).when(col("result") === "draw", 0.5).otherwise(0.0).as("score"),
      when(col("result") === "white", 1).otherwise(0).as("wins"),
      when(col("result") === "draw",  1).otherwise(0).as("draws"),
      when(col("result") === "black", 1).otherwise(0).as("losses")
    )
    val blackView = gameFinished.select(
      col("blackBotFamily").as("family"),
      when(col("result") === "black", 1.0).when(col("result") === "draw", 0.5).otherwise(0.0).as("score"),
      when(col("result") === "black", 1).otherwise(0).as("wins"),
      when(col("result") === "draw",  1).otherwise(0).as("draws"),
      when(col("result") === "white", 1).otherwise(0).as("losses")
    )
    whiteView.union(blackView)
      .groupBy("family")
      .agg(
        count("family").as("games"),
        sum("wins").as("wins"),
        sum("losses").as("losses"),
        sum("draws").as("draws"),
        round(sum("score"), 1).as("totalScore")
      )
      .withColumn("winRate", round(col("wins").cast("double") / col("games"), 3))
      .orderBy(desc("totalScore"))
  }

  def computeStrategyComparison(gameFinished: DataFrame): DataFrame = {
    val whiteView = gameFinished.select(
      col("whiteStrategyType").as("strategyType"),
      when(col("result") === "white", 1.0).when(col("result") === "draw", 0.5).otherwise(0.0).as("score"),
      when(col("result") === "white", 1).otherwise(0).as("wins"),
      when(col("result") === "draw",  1).otherwise(0).as("draws"),
      when(col("result") === "black", 1).otherwise(0).as("losses")
    )
    val blackView = gameFinished.select(
      col("blackStrategyType").as("strategyType"),
      when(col("result") === "black", 1.0).when(col("result") === "draw", 0.5).otherwise(0.0).as("score"),
      when(col("result") === "black", 1).otherwise(0).as("wins"),
      when(col("result") === "draw",  1).otherwise(0).as("draws"),
      when(col("result") === "white", 1).otherwise(0).as("losses")
    )
    whiteView.union(blackView)
      .groupBy("strategyType")
      .agg(
        count("strategyType").as("games"),
        sum("wins").as("wins"),
        sum("losses").as("losses"),
        sum("draws").as("draws"),
        round(sum("score"), 1).as("totalScore")
      )
      .withColumn("winRate", round(col("wins").cast("double") / col("games"), 3))
      .orderBy(desc("totalScore"))
  }

  def computeColorPerformance(gameFinished: DataFrame): DataFrame = {
    val whiteStats = gameFinished
      .groupBy(col("whiteBotId").as("botId"))
      .agg(
        count("*").as("gamesAsWhite"),
        sum(when(col("result") === "white", 1).otherwise(0)).as("whiteWins"),
        round(
          sum(when(col("result") === "white", 1.0).when(col("result") === "draw", 0.5).otherwise(0.0)),
          1
        ).as("whiteScore")
      )
    val blackStats = gameFinished
      .groupBy(col("blackBotId").as("botId"))
      .agg(
        count("*").as("gamesAsBlack"),
        sum(when(col("result") === "black", 1).otherwise(0)).as("blackWins"),
        round(
          sum(when(col("result") === "black", 1.0).when(col("result") === "draw", 0.5).otherwise(0.0)),
          1
        ).as("blackScore")
      )
    whiteStats
      .join(blackStats, Seq("botId"), "outer")
      .na.fill(0L,  Seq("gamesAsWhite", "whiteWins", "gamesAsBlack", "blackWins"))
      .na.fill(0.0, Seq("whiteScore", "blackScore"))
      .orderBy("botId")
  }

  def computeFastestWins(gameFinished: DataFrame): DataFrame =
    gameFinished
      .filter(col("result") =!= "draw")
      .groupBy(col("winnerBotId"))
      .agg(
        count("*").as("decisiveGames"),
        round(avg("totalPly"), 1).as("avgWinPly"),
        min("totalPly").as("minWinPly"),
        round(avg("durationMillis"), 0).as("avgWinDurationMs")
      )
      .orderBy("avgWinPly")

  def computeStockfishComparison(gameFinished: DataFrame): DataFrame = {
    val whiteView = gameFinished
      .filter(col("whiteBotFamily") === "uci-engine" && col("whiteEngineType") === "stockfish")
      .select(
        col("whiteBotId").as("botId"),
        col("whiteStrategyType").as("strategyType"),
        when(col("result") === "white", 1.0).when(col("result") === "draw", 0.5).otherwise(0.0).as("score"),
        when(col("result") === "white", 1).otherwise(0).as("wins"),
        when(col("result") === "draw",  1).otherwise(0).as("draws"),
        col("totalPly")
      )
    val blackView = gameFinished
      .filter(col("blackBotFamily") === "uci-engine" && col("blackEngineType") === "stockfish")
      .select(
        col("blackBotId").as("botId"),
        col("blackStrategyType").as("strategyType"),
        when(col("result") === "black", 1.0).when(col("result") === "draw", 0.5).otherwise(0.0).as("score"),
        when(col("result") === "black", 1).otherwise(0).as("wins"),
        when(col("result") === "draw",  1).otherwise(0).as("draws"),
        col("totalPly")
      )
    whiteView.union(blackView)
      .groupBy("botId", "strategyType")
      .agg(
        count("botId").as("games"),
        sum("wins").as("wins"),
        sum("draws").as("draws"),
        round(sum("score"), 1).as("totalScore"),
        round(avg("totalPly"), 1).as("avgGameLength")
      )
      .withColumn("winRate", round(col("wins").cast("double") / col("games"), 3))
      .orderBy(desc("totalScore"))
  }

  def computeFamilyMatchups(gameFinished: DataFrame): DataFrame =
    gameFinished
      .groupBy("whiteBotFamily", "blackBotFamily")
      .agg(
        count("*").as("games"),
        round(
          sum(when(col("result") === "white", 1.0).when(col("result") === "draw", 0.5).otherwise(0.0)),
          1
        ).as("whiteScore"),
        round(
          sum(when(col("result") === "black", 1.0).when(col("result") === "draw", 0.5).otherwise(0.0)),
          1
        ).as("blackScore"),
        sum(when(col("result") === "draw", 1).otherwise(0)).as("draws")
      )
      .orderBy("whiteBotFamily", "blackBotFamily")

  def computeSearchessAiComparison(gameFinished: DataFrame): DataFrame = {
    val botId = "searchess-ai-v1"

    val asWhite = gameFinished
      .filter(col("whiteBotId") === botId)
      .select(
        col("blackBotId").as("opponentBotId"),
        col("blackBotFamily").as("opponentFamily"),
        when(col("result") === "white", 1.0).when(col("result") === "draw", 0.5).otherwise(0.0).as("score"),
        when(col("result") === "white", 1).otherwise(0).as("wins"),
        when(col("result") === "draw",  1).otherwise(0).as("draws"),
        when(col("result") === "black", 1).otherwise(0).as("losses"),
        col("totalPly")
      )
    val asBlack = gameFinished
      .filter(col("blackBotId") === botId)
      .select(
        col("whiteBotId").as("opponentBotId"),
        col("whiteBotFamily").as("opponentFamily"),
        when(col("result") === "black", 1.0).when(col("result") === "draw", 0.5).otherwise(0.0).as("score"),
        when(col("result") === "black", 1).otherwise(0).as("wins"),
        when(col("result") === "draw",  1).otherwise(0).as("draws"),
        when(col("result") === "white", 1).otherwise(0).as("losses"),
        col("totalPly")
      )

    asWhite.union(asBlack)
      .groupBy("opponentBotId", "opponentFamily")
      .agg(
        count("opponentBotId").as("games"),
        sum("wins").as("searchessAiWins"),
        sum("draws").as("draws"),
        sum("losses").as("losses"),
        round(sum("score"), 1).as("score"),
        round(avg("totalPly"), 1).as("avgGameLength")
      )
      .withColumn("winRate", round(col("searchessAiWins").cast("double") / col("games"), 3))
      .orderBy(desc("score"))
  }

  def run(
    spark:      SparkSession,
    inputPath:  String,
    outputPath: String,
    pgConfig:   Option[PostgresConfig] = None,
    lakeConfig: SparkLakeConfig = SparkLakeConfig.fromEnv()
  ): Unit = {
    runWithResult(spark, inputPath, outputPath, pgConfig, lakeConfig)
    ()
  }

  def runWithResult(
    spark:      SparkSession,
    inputPath:  String,
    outputPath: String,
    pgConfig:   Option[PostgresConfig] = None,
    lakeConfig: SparkLakeConfig = SparkLakeConfig.fromEnv()
  ): AnalyticsRunResult = {
    val runId        = UUID.randomUUID().toString
    val bronzeEvents = BronzeEvents.loadEvents(spark, inputPath).cache()
    val silver       = AnalyticsPipeline.buildSilver(bronzeEvents)
    val gameFinished = silver.gameFinished.cache()
    val gold         = AnalyticsPipeline.buildGold(gameFinished)
    val count        = gameFinished.count()
    println(s"\n=== Arena Analytics: $count games loaded from $inputPath ===\n")

    val dataQuality = AnalyticsPipeline.buildDataQuality(gameFinished)
    println("--- Data Quality: GameFinished ---")
    dataQuality.show(truncate = false)

    val leaderboard        = gold.leaderboard
    val headToHead         = gold.headToHead
    val terminations       = gold.terminations
    val avgLength          = gold.avgLength
    val familyComparison   = gold.familyComparison
    val strategyComparison = gold.strategyComparison
    val colorPerformance   = gold.colorPerformance
    val fastestWins        = gold.fastestWins
    val stockfishComp      = gold.stockfishComp
    val familyMatchups     = gold.familyMatchups
    val searchessAiComp    = gold.searchessAiComp
    val eloRatings         = gold.eloRatings

    println("--- Leaderboard ---")
    leaderboard.show(truncate = false)
    println("--- Elo Ratings ---")
    eloRatings.show(truncate = false)
    println("--- Head-to-Head ---")
    headToHead.show(truncate = false)
    println("--- Termination Reasons ---")
    terminations.show(truncate = false)
    println("--- Avg Game Length by Pairing ---")
    avgLength.show(truncate = false)
    println("--- Bot Family Comparison ---")
    familyComparison.show(truncate = false)
    println("--- Strategy Type Comparison ---")
    strategyComparison.show(truncate = false)
    println("--- White/Black Performance per Bot ---")
    colorPerformance.show(truncate = false)
    println("--- Fastest Winning Bots ---")
    fastestWins.show(truncate = false)
    println("--- Stockfish Configuration Comparison ---")
    stockfishComp.show(truncate = false)
    println("--- Family Matchups ---")
    familyMatchups.show(truncate = false)
    println("--- SearchessAI vs Opponents ---")
    searchessAiComp.show(truncate = false)

    var csvFailed = false
    def writeCsv(df: DataFrame, name: String): Unit =
      try {
        df.coalesce(1).write.mode("overwrite").option("header", "true").csv(s"$outputPath/$name")
      } catch {
        case _: Exception => csvFailed = true
      }

    writeCsv(leaderboard,        "leaderboard")
    writeCsv(headToHead,         "head-to-head")
    writeCsv(terminations,       "terminations")
    writeCsv(avgLength,          "avg-game-length")
    writeCsv(familyComparison,   "bot-family-comparison")
    writeCsv(strategyComparison, "strategy-comparison")
    writeCsv(colorPerformance,   "color-performance")
    writeCsv(fastestWins,        "fastest-wins")
    writeCsv(stockfishComp,      "stockfish-comparison")
    writeCsv(familyMatchups,     "family-matchups")
    writeCsv(searchessAiComp,    "searchess-ai-comparison")
    writeCsv(eloRatings,         "elo-ratings")

    if (!csvFailed) {
      println(s"\nCSV output written to $outputPath")
    } else {
      println(s"\n[WARN] CSV write skipped — Hadoop native libraries (winutils.exe) not available.")
      println(s"       Console tables above are the complete analytics result.")
      println(s"       See docs/architecture/bot-arena-spark-demo.md for the Windows workaround.")
    }

    if (lakeConfig.writeEnabled) {
      val lakeWriter = new ParquetLakeWriter(lakeConfig, runId, inputPath)
      println(s"\n=== Writing Spark lake Parquet [run_id=$runId, mode=${lakeConfig.writeMode}, base=${lakeConfig.basePath}] ===")

      lakeWriter.writeTable(bronzeEvents,                           "bronze", "events")
      lakeWriter.writeTable(silver.gameStarted,                     "silver", "game-started")
      lakeWriter.writeTable(silver.movePlayed,                      "silver", "move-played")
      lakeWriter.writeTable(gameFinished,                           "silver", "game-finished")
      lakeWriter.writeTable(leaderboard,                            "gold",   "leaderboard")
      lakeWriter.writeTable(headToHead,                             "gold",   "head-to-head")
      lakeWriter.writeTable(terminations,                           "gold",   "terminations")
      lakeWriter.writeTable(avgLength,                              "gold",   "avg-game-length")
      lakeWriter.writeTable(familyComparison,                       "gold",   "bot-family-comparison")
      lakeWriter.writeTable(strategyComparison,                     "gold",   "strategy-comparison")
      lakeWriter.writeTable(colorPerformance,                       "gold",   "color-performance")
      lakeWriter.writeTable(fastestWins,                            "gold",   "fastest-wins")
      lakeWriter.writeTable(stockfishComp,                          "gold",   "stockfish-comparison")
      lakeWriter.writeTable(familyMatchups,                         "gold",   "family-matchups")
      lakeWriter.writeTable(searchessAiComp,                        "gold",   "searchess-ai-comparison")
      lakeWriter.writeTable(eloRatings,                             "gold",   "elo-ratings")
      lakeWriter.writeTable(dataQuality,                            "gold",   "data-quality")
    }

    pgConfig.foreach { cfg =>
      val writer = new PostgresWriter(cfg, runId, inputPath)
      println(s"\n=== Writing analytics to PostgreSQL [run_id=$runId, mode=${cfg.writeMode}] ===")
      writer.createSchemaIfNeeded()

      var pgSucceeded = 0
      var pgFailed    = List.empty[String]
      def pg(df: DataFrame, name: String): Unit = {
        if (writer.writeTable(df, name)) pgSucceeded += 1
        else pgFailed = pgFailed :+ name
      }

      pg(leaderboard,        "analytics_leaderboard")
      pg(headToHead,         "analytics_head_to_head")
      pg(avgLength,          "analytics_avg_game_length")
      pg(terminations,       "analytics_terminations")
      pg(familyComparison,   "analytics_bot_family_comparison")
      pg(strategyComparison, "analytics_strategy_comparison")
      pg(colorPerformance,   "analytics_color_performance")
      pg(fastestWins,        "analytics_fastest_wins")
      pg(stockfishComp,      "analytics_stockfish_comparison")
      pg(familyMatchups,     "analytics_family_matchups")
      pg(searchessAiComp,    "analytics_searchess_ai_comparison")
      pg(eloRatings,         "analytics_elo_ratings")

      val total = pgSucceeded + pgFailed.length
      if (pgFailed.isEmpty) {
        println(s"[PG] Done. $pgSucceeded of $total tables written to ${cfg.schema}.*")
      } else {
        println(s"[PG WARN] $pgSucceeded of $total tables written; ${pgFailed.length} failed: ${pgFailed.mkString(", ")}")
        if (cfg.strictWrite)
          throw new RuntimeException(s"PostgreSQL strict write failed for: ${pgFailed.mkString(", ")}")
      }
    }

    gameFinished.unpersist()
    bronzeEvents.unpersist()
    AnalyticsRunResult(runId, inputPath, outputPath)
  }
}
