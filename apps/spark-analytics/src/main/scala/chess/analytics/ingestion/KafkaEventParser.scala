package chess.analytics.ingestion

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.functions._
import chess.analytics.schema.GameEventSchemas

object KafkaEventParser {

  def parseEvents(kafkaValues: DataFrame): DataFrame =
    kafkaValues
      .select(from_json(col("value").cast("string"), GameEventSchemas.eventSchema).as("event"))
      .select("event.*")
      .drop(GameEventSchemas.corruptRecordColumn)
}
