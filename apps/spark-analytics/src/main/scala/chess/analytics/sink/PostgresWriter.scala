package chess.analytics.sink

import org.apache.spark.sql.{DataFrame, functions => F}
import java.time.Instant
import chess.analytics.config.PostgresConfig

final class PostgresWriter(config: PostgresConfig, runId: String, sourcePath: String) {

  private val createdAt: String = Instant.now().toString

  private[analytics] def qualifiedTableName(tableName: String): String =
    s"${config.schema}.$tableName"

  def writeTable(df: DataFrame, tableName: String): Boolean = {
    val enriched = df
      .withColumn("run_id",      F.lit(runId))
      .withColumn("source_path", F.lit(sourcePath))
      .withColumn("created_at",  F.lit(createdAt))

    try {
      enriched.write
        .mode(config.writeMode)
        .format("jdbc")
        .option("url",      config.url)
        .option("dbtable",  qualifiedTableName(tableName))
        .option("user",     config.user)
        .option("password", config.password)
        .option("driver",   "org.postgresql.Driver")
        .save()
      println(s"[PG] Written: ${qualifiedTableName(tableName)}")
      true
    } catch {
      case e: Exception =>
        println(s"[PG ERROR] Failed to write $tableName: ${e.getMessage}")
        false
    }
  }

  def createSchemaIfNeeded(): Unit = {
    if (config.schema != "public") {
      try {
        val conn = java.sql.DriverManager.getConnection(config.url, config.user, config.password)
        try {
          val stmt = conn.createStatement()
          stmt.execute(s"""CREATE SCHEMA IF NOT EXISTS "${config.schema}"""")
          stmt.close()
          println(s"[PG] Schema ensured: ${config.schema}")
        } finally {
          conn.close()
        }
      } catch {
        case e: Exception =>
          println(s"[PG WARN] Could not create schema '${config.schema}': ${e.getMessage}")
      }
    }
  }
}
