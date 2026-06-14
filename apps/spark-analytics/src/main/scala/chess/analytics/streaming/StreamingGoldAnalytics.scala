package chess.analytics.streaming

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions._

object StreamingGoldAnalytics {

  def liveLeaderboard(gameFinished: DataFrame): DataFrame = {
    val whiteView = gameFinished.select(
      col("whiteBotId").as("botId"),
      when(col("result") === "white", 1.0).when(col("result") === "draw", 0.5).otherwise(0.0).as("score"),
      when(col("result") === "white", 1).otherwise(0).as("wins"),
      when(col("result") === "draw",  1).otherwise(0).as("draws"),
      when(col("result") === "black", 1).otherwise(0).as("losses")
    )

    val blackView = gameFinished.select(
      col("blackBotId").as("botId"),
      when(col("result") === "black", 1.0).when(col("result") === "draw", 0.5).otherwise(0.0).as("score"),
      when(col("result") === "black", 1).otherwise(0).as("wins"),
      when(col("result") === "draw",  1).otherwise(0).as("draws"),
      when(col("result") === "white", 1).otherwise(0).as("losses")
    )

    whiteView.union(blackView)
      .groupBy("botId")
      .agg(
        sum("score").as("totalScore"),
        sum("wins").as("wins"),
        sum("draws").as("draws"),
        sum("losses").as("losses"),
        count("botId").as("gamesPlayed")
      )
      .withColumn("winRate", round(col("wins").cast("double") / col("gamesPlayed"), 3))
  }

  def terminations(gameFinished: DataFrame): DataFrame =
    gameFinished
      .groupBy("terminationReason")
      .agg(count("*").as("count"))

  def botFamilyComparison(gameFinished: DataFrame): DataFrame = {
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
  }
}
