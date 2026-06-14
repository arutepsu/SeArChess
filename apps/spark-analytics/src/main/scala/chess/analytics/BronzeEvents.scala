package chess.analytics

import org.apache.spark.sql.{DataFrame, SparkSession}

object BronzeEvents {

  def loadEvents(spark: SparkSession, path: String): DataFrame =
    spark.read
      .schema(GameEventSchemas.eventSchema)
      .option("mode", "PERMISSIVE")
      .option("columnNameOfCorruptRecord", GameEventSchemas.corruptRecordColumn)
      .json(path)
      .drop(GameEventSchemas.corruptRecordColumn)
}
