package chess.analytics.sink

import org.apache.spark.sql.{DataFrame, functions => F}
import java.time.Instant
import chess.analytics.config.SparkLakeConfig

final class ParquetLakeWriter(config: SparkLakeConfig, runId: String, sourcePath: String) {

  private val createdAt: String = Instant.now().toString

  def writeTable(df: DataFrame, layer: String, name: String): Boolean = {
    val enriched = df
      .withColumn("run_id",      F.lit(runId))
      .withColumn("source_path", F.lit(sourcePath))
      .withColumn("created_at",  F.lit(createdAt))
      .withColumn("layer",       F.lit(layer))

    val writer = enriched.write.mode(config.writeMode)
    val partitioned =
      if (config.partitionByRunId) writer.partitionBy("run_id")
      else writer

    try {
      partitioned.parquet(path(layer, name))
      println(s"[LAKE] Written: ${path(layer, name)}")
      true
    } catch {
      case e: Exception =>
        println(s"[LAKE ERROR] Failed to write $layer/$name: ${e.getMessage}")
        false
    }
  }

  private[analytics] def path(layer: String, name: String): String =
    s"${config.basePath.stripSuffix("/")}/$layer/$name"
}
