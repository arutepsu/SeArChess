package chess.analytics.ingestion

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions._
import chess.analytics.schema.GameEventSchemas

object LiveGameEventParser {

  def parseAndFilter(kafkaRaw: DataFrame): DataFrame = {
    val parsed = kafkaRaw
      .selectExpr("CAST(value AS STRING) AS json_value")
      .select(from_json(col("json_value"), GameEventSchemas.envelopeSchema).as("e"))
      .select("e.*")
      .drop(GameEventSchemas.corruptRecordColumn)

    parsed
      .filter(col("eventType") === "GameFinished")
      .filter(col("eventId").isNotNull && col("aggregateId").isNotNull && col("occurredAt").isNotNull)
      .select(
        col("eventId").as("event_id"),
        col("aggregateId").as("aggregate_id"),
        col("payload.sessionId").as("session_id"),
        col("occurredAt").as("occurred_at"),
        col("payload.result").as("result"),
        col("payload.winner").as("winner"),
        col("payload.drawReason").as("draw_reason"),
        col("correlationId").as("correlation_id")
      )
      .filter(col("result").isNotNull)
  }
}
