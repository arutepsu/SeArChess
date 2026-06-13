package chess.analytics

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.StreamingQuery
import org.apache.spark.sql.types._

object GameAnalyticsStreamingJob {

  final case class Config(
      bootstrapServers: String = "localhost:9092",
      topic: String = "game-events",
      checkpointLocation: String = "target/spark-streaming-checkpoints/game-events"
  )

  def main(args: Array[String]): Unit = {
    val config = Config(
      bootstrapServers = argOrEnv(args, 0, "KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
      topic = argOrEnv(args, 1, "KAFKA_GAME_EVENTS_TOPIC", "game-events"),
      checkpointLocation = argOrEnv(
        args,
        2,
        "SPARK_STREAMING_CHECKPOINT",
        "target/spark-streaming-checkpoints/game-events"
      )
    )

    val spark = SparkSession.builder()
      .appName("Arena Game Analytics Streaming")
      .master("local[*]")
      .config("spark.sql.shuffle.partitions", "4")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .getOrCreate()

    spark.sparkContext.setLogLevel("WARN")

    try {
      val events = readKafkaEvents(spark, config)
      val gameFinished = parseGameFinished(events)

      println("=== Arena Streaming Analytics ===")
      println(s"Bootstrap : ${config.bootstrapServers}")
      println(s"Topic     : ${config.topic}")
      println(s"Checkpoint: ${config.checkpointLocation}")
      println()

      val queries = Seq(
        consoleQuery(
          gameFinished
            .groupBy(coalesce(col("winnerBotId"), lit("draw")).as("winnerBotId"))
            .agg(count("*").as("finishedGames"))
            .orderBy(desc("finishedGames")),
          "finished-games-by-winner",
          s"${config.checkpointLocation}/winner"
        ),
        consoleQuery(
          gameFinished
            .groupBy(col("result"))
            .agg(count("*").as("finishedGames"))
            .orderBy(desc("finishedGames")),
          "finished-games-by-result",
          s"${config.checkpointLocation}/result"
        ),
        consoleQuery(
          gameFinished
            .groupBy(col("terminationReason"))
            .agg(count("*").as("finishedGames"))
            .orderBy(desc("finishedGames")),
          "finished-games-by-termination",
          s"${config.checkpointLocation}/termination"
        )
      )

      spark.streams.awaitAnyTermination()
      queries.foreach(_.stop())
    } finally {
      spark.stop()
    }
  }

  private[analytics] def parseGameFinished(events: DataFrame): DataFrame =
    events
      .select(from_json(col("value"), eventSchema).as("event"))
      .select("event.*")
      .filter(col("eventType") === "GameFinished")

  private def readKafkaEvents(spark: SparkSession, config: Config): DataFrame =
    spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", config.bootstrapServers)
      .option("subscribe", config.topic)
      .option("startingOffsets", "latest")
      .load()
      .selectExpr("CAST(value AS STRING) AS value")

  private def consoleQuery(df: DataFrame, queryName: String, checkpointLocation: String): StreamingQuery =
    df.writeStream
      .queryName(queryName)
      .format("console")
      .outputMode("complete")
      .option("truncate", "false")
      .option("numRows", "50")
      .option("checkpointLocation", checkpointLocation)
      .start()

  private def argOrEnv(args: Array[String], index: Int, envName: String, defaultValue: String): String =
    args.lift(index).filter(_.nonEmpty)
      .orElse(sys.env.get(envName).filter(_.nonEmpty))
      .getOrElse(defaultValue)

  private val eventSchema: StructType = StructType(Seq(
    StructField("schemaVersion", StringType, nullable = true),
    StructField("eventType", StringType, nullable = true),
    StructField("eventId", StringType, nullable = true),
    StructField("timestamp", StringType, nullable = true),
    StructField("tournamentId", StringType, nullable = true),
    StructField("gameId", StringType, nullable = true),
    StructField("whiteBotId", StringType, nullable = true),
    StructField("blackBotId", StringType, nullable = true),
    StructField("winnerBotId", StringType, nullable = true),
    StructField("loserBotId", StringType, nullable = true),
    StructField("result", StringType, nullable = true),
    StructField("terminationReason", StringType, nullable = true)
  ))
}
