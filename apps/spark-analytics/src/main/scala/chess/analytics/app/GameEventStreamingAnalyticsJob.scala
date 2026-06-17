package chess.analytics.app

import org.apache.spark.sql.{DataFrame => SparkDataFrame}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.streaming.Trigger
import chess.analytics.config.LiveGameAnalyticsConfig
import chess.analytics.ingestion.LiveGameEventParser
import chess.analytics.sink.LiveGameResultsSink

object GameEventStreamingAnalyticsJob {

  def main(args: Array[String]): Unit = {
    val config = LiveGameAnalyticsConfig.fromEnv()

    LiveGameResultsSink.ensureTableExists(config.postgres)

    val spark = SparkSession.builder()
      .appName("Live Game Event Streaming Analytics")
      .master("local[*]")
      .config("spark.sql.shuffle.partitions", "4")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .getOrCreate()

    spark.sparkContext.setLogLevel("WARN")

    println("=== Live Game Event Streaming Analytics ===")
    println(s"Bootstrap : ${config.bootstrapServers}")
    println(s"Topic     : ${config.topic}")
    println(s"Offsets   : ${config.startingOffsets}")
    println(s"Trigger   : ${config.triggerSeconds}s")
    println(s"Checkpoint: ${config.checkpointDir}")
    println()

    try {
      val kafkaRaw = spark.readStream
        .format("kafka")
        .option("kafka.bootstrap.servers", config.bootstrapServers)
        .option("subscribe", config.topic)
        .option("startingOffsets", config.startingOffsets)
        .load()

      val gameFinishedRows = LiveGameEventParser.parseAndFilter(kafkaRaw)

      val query = gameFinishedRows.writeStream
        .queryName("live-game-results")
        .outputMode("append")
        .foreachBatch { (batch: SparkDataFrame, _: Long) =>
          LiveGameResultsSink.writeBatch(batch, config.postgres)
        }
        .option("checkpointLocation", config.checkpointDir)
        .trigger(Trigger.ProcessingTime(s"${config.triggerSeconds} seconds"))
        .start()

      spark.streams.awaitAnyTermination()
      query.stop()
    } finally {
      spark.stop()
    }
  }
}
