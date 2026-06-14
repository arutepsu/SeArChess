package chess.analytics.streaming

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions._

object WindowedStreamingAnalytics {

  def gamesFinishedPerMinute(gameFinished: DataFrame): DataFrame =
    withEventTime(gameFinished)
      .groupBy(window(col("eventTime"), "1 minute"))
      .agg(count("*").as("gamesFinished"))
      .select(
        col("window.start").as("windowStart"),
        col("window.end").as("windowEnd"),
        col("gamesFinished")
      )

  def avgGameDurationPerWindow(gameFinished: DataFrame): DataFrame =
    withEventTime(gameFinished)
      .groupBy(window(col("eventTime"), "5 minutes"))
      .agg(
        count("*").as("games"),
        round(avg("durationMillis"), 0).as("avgDurationMillis")
      )
      .select(
        col("window.start").as("windowStart"),
        col("window.end").as("windowEnd"),
        col("games"),
        col("avgDurationMillis")
      )

  def botWinRatePerWindow(gameFinished: DataFrame): DataFrame = {
    val events = withEventTime(gameFinished)

    val whiteView = events.select(
      col("eventTime"),
      col("whiteBotId").as("botId"),
      when(col("result") === "white", 1).otherwise(0).as("wins"),
      when(col("result") === "draw",  1).otherwise(0).as("draws"),
      when(col("result") === "black", 1).otherwise(0).as("losses")
    )

    val blackView = events.select(
      col("eventTime"),
      col("blackBotId").as("botId"),
      when(col("result") === "black", 1).otherwise(0).as("wins"),
      when(col("result") === "draw",  1).otherwise(0).as("draws"),
      when(col("result") === "white", 1).otherwise(0).as("losses")
    )

    whiteView.union(blackView)
      .groupBy(window(col("eventTime"), "5 minutes"), col("botId"))
      .agg(
        count("botId").as("gamesPlayed"),
        sum("wins").as("wins"),
        sum("draws").as("draws"),
        sum("losses").as("losses")
      )
      .withColumn("winRate", round(col("wins").cast("double") / col("gamesPlayed"), 3))
      .select(
        col("window.start").as("windowStart"),
        col("window.end").as("windowEnd"),
        col("botId"),
        col("gamesPlayed"),
        col("wins"),
        col("draws"),
        col("losses"),
        col("winRate")
      )
  }

  def moveThroughputPerMinute(movePlayed: DataFrame): DataFrame =
    withEventTime(movePlayed)
      .groupBy(window(col("eventTime"), "1 minute"))
      .agg(count("*").as("movesPlayed"))
      .select(
        col("window.start").as("windowStart"),
        col("window.end").as("windowEnd"),
        col("movesPlayed")
      )

  private def withEventTime(df: DataFrame): DataFrame =
    df.withColumn("eventTime", to_timestamp(col("timestamp")))
}
