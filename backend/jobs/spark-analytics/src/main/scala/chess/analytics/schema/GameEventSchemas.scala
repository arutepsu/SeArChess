package chess.analytics.schema

import org.apache.spark.sql.types._

object GameEventSchemas {

  val corruptRecordColumn: String = "_corrupt_record"

  val eventSchema: StructType = StructType(Seq(
    StructField("schemaVersion", StringType, nullable = true),
    StructField("eventType", StringType, nullable = true),
    StructField("eventId", StringType, nullable = true),
    StructField("timestamp", StringType, nullable = true),
    StructField("tournamentId", StringType, nullable = true),
    StructField("gameId", StringType, nullable = true),

    StructField("whiteBotId", StringType, nullable = true),
    StructField("blackBotId", StringType, nullable = true),
    StructField("whiteBotFamily", StringType, nullable = true),
    StructField("blackBotFamily", StringType, nullable = true),
    StructField("whiteStrategyType", StringType, nullable = true),
    StructField("blackStrategyType", StringType, nullable = true),
    StructField("whiteEngineType", StringType, nullable = true),
    StructField("blackEngineType", StringType, nullable = true),
    StructField("whiteModelVersion", StringType, nullable = true),
    StructField("blackModelVersion", StringType, nullable = true),

    StructField("botId", StringType, nullable = true),
    StructField("moveUci", StringType, nullable = true),
    StructField("plyNumber", LongType, nullable = true),

    StructField("winnerBotId", StringType, nullable = true),
    StructField("loserBotId", StringType, nullable = true),
    StructField("result", StringType, nullable = true),
    StructField("totalMoves", LongType, nullable = true),
    StructField("totalPly", LongType, nullable = true),
    StructField("durationMillis", LongType, nullable = true),
    StructField("terminationReason", StringType, nullable = true),

    StructField(corruptRecordColumn, StringType, nullable = true)
  ))

  // ── Production game-events topic: EventEnvelope wrapping AppEvent payloads ──
  // Field names are camelCase to match the JSON wire format produced by EventEnvelope.writePayloadEnvelope.
  // Only the GameFinished payload fields are defined here; other event types will have null payload fields.

  private val envelopePayloadSchema: StructType = StructType(Seq(
    StructField("type",       StringType, nullable = true),
    StructField("sessionId",  StringType, nullable = true),
    StructField("gameId",     StringType, nullable = true),
    StructField("result",     StringType, nullable = true),
    StructField("winner",     StringType, nullable = true),
    StructField("drawReason", StringType, nullable = true)
  ))

  val envelopeSchema: StructType = StructType(Seq(
    StructField("eventId",       StringType,           nullable = true),
    StructField("eventType",     StringType,           nullable = true),
    StructField("eventVersion",  IntegerType,          nullable = true),
    StructField("occurredAt",    StringType,           nullable = true),
    StructField("producer",      StringType,           nullable = true),
    StructField("correlationId", StringType,           nullable = true),
    StructField("causationId",   StringType,           nullable = true),
    StructField("aggregateType", StringType,           nullable = true),
    StructField("aggregateId",   StringType,           nullable = true),
    StructField("payload",       envelopePayloadSchema, nullable = true),
    StructField(corruptRecordColumn, StringType,       nullable = true)
  ))
}
