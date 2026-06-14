package chess.analytics.ingestion

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.LongType

object SilverEvents {

  def gameStarted(events: DataFrame): DataFrame =
    events
      .filter(col("eventType") === "GameStarted")
      .select(
        col("schemaVersion"),
        col("eventType"),
        col("eventId"),
        col("timestamp"),
        col("tournamentId"),
        col("gameId"),
        col("whiteBotId"),
        col("blackBotId"),
        col("whiteBotFamily"),
        col("blackBotFamily"),
        col("whiteStrategyType"),
        col("blackStrategyType"),
        col("whiteEngineType"),
        col("blackEngineType"),
        col("whiteModelVersion"),
        col("blackModelVersion")
      )

  def movePlayed(events: DataFrame): DataFrame =
    events
      .filter(col("eventType") === "MovePlayed")
      .select(
        col("schemaVersion"),
        col("eventType"),
        col("eventId"),
        col("timestamp"),
        col("tournamentId"),
        col("gameId"),
        col("botId"),
        col("moveUci"),
        col("plyNumber").cast(LongType).as("plyNumber"),
        col("durationMillis").cast(LongType).as("durationMillis")
      )

  def gameFinished(events: DataFrame): DataFrame =
    events
      .filter(col("eventType") === "GameFinished")
      .select(
        col("schemaVersion"),
        col("eventType"),
        col("eventId"),
        col("timestamp"),
        col("tournamentId"),
        col("gameId"),
        col("whiteBotId"),
        col("blackBotId"),
        col("winnerBotId"),
        col("loserBotId"),
        col("result"),
        col("whiteBotFamily"),
        col("blackBotFamily"),
        col("whiteStrategyType"),
        col("blackStrategyType"),
        col("whiteEngineType"),
        col("blackEngineType"),
        col("whiteModelVersion"),
        col("blackModelVersion"),
        col("totalMoves").cast(LongType).as("totalMoves"),
        col("totalPly").cast(LongType).as("totalPly"),
        col("durationMillis").cast(LongType).as("durationMillis"),
        col("terminationReason")
      )
}
