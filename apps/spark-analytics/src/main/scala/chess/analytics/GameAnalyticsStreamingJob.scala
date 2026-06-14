package chess.analytics

import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.streaming.StreamingQuery
import org.apache.spark.sql.streaming.Trigger

object GameAnalyticsStreamingJob {

  def main(args: Array[String]): Unit = {
    val config = configFromArgsOrEnv(args)

    val spark = SparkSession.builder()
      .appName("Arena Game Analytics Streaming")
      .master("local[*]")
      .config("spark.sql.shuffle.partitions", "4")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .getOrCreate()

    spark.sparkContext.setLogLevel("WARN")

    try {
      val events = readKafkaEvents(spark, config)
      val parsedEvents = KafkaEventParser.parseEvents(events)
      val gameFinished = SilverEvents.gameFinished(parsedEvents)
      val movePlayed = SilverEvents.movePlayed(parsedEvents)

      println("=== Arena Streaming Analytics ===")
      println(s"Bootstrap : ${config.bootstrapServers}")
      println(s"Topic     : ${config.topic}")
      println(s"Offsets   : ${config.startingOffsets}")
      println(s"Query     : ${config.query}")
      println(s"Trigger   : ${config.triggerSeconds}s")
      println(s"Checkpoint: ${config.checkpointLocation(config.query)}")
      println()

      val query = consoleQuery(
        streamingOutput(config.query, gameFinished, movePlayed),
        config.query,
        config.checkpointLocation(config.query),
        config.triggerSeconds
      )

      spark.streams.awaitAnyTermination()
      query.stop()
    } finally {
      spark.stop()
    }
  }

  private[analytics] def parseGameFinished(events: DataFrame): DataFrame =
    SilverEvents.gameFinished(KafkaEventParser.parseEvents(events))

  private[analytics] def streamingOutput(query: String, gameFinished: DataFrame, movePlayed: DataFrame): DataFrame =
    query match {
      case "leaderboard" =>
        StreamingGoldAnalytics.liveLeaderboard(gameFinished)
      case "terminations" =>
        StreamingGoldAnalytics.terminations(gameFinished)
      case "bot-families" =>
        StreamingGoldAnalytics.botFamilyComparison(gameFinished)
      case "games-per-minute" =>
        WindowedStreamingAnalytics.gamesFinishedPerMinute(gameFinished)
      case "avg-duration-window" =>
        WindowedStreamingAnalytics.avgGameDurationPerWindow(gameFinished)
      case "win-rate-window" =>
        WindowedStreamingAnalytics.botWinRatePerWindow(gameFinished)
      case "move-throughput" =>
        WindowedStreamingAnalytics.moveThroughputPerMinute(movePlayed)
      case other =>
        throw new IllegalArgumentException(s"Unsupported streaming query: $other")
    }

  private def readKafkaEvents(spark: SparkSession, config: StreamingAnalyticsConfig): DataFrame =
    spark.readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", config.bootstrapServers)
      .option("subscribe", config.topic)
      .option("startingOffsets", config.startingOffsets)
      .load()
      .selectExpr("CAST(value AS STRING) AS value")

  private def consoleQuery(
    df:                 DataFrame,
    queryName:          String,
    checkpointLocation: String,
    triggerSeconds:     Int
  ): StreamingQuery =
    df.writeStream
      .queryName(queryName)
      .format("console")
      .outputMode("complete")
      .option("truncate", "false")
      .option("numRows", "50")
      .option("checkpointLocation", checkpointLocation)
      .trigger(Trigger.ProcessingTime(s"$triggerSeconds seconds"))
      .start()

  private def configFromArgsOrEnv(args: Array[String]): StreamingAnalyticsConfig = {
    val argEnv = Map(
      "KAFKA_BOOTSTRAP_SERVERS" -> args.lift(0),
      "KAFKA_TOPIC" -> args.lift(1),
      "SPARK_CHECKPOINT_DIR" -> args.lift(2),
      "SPARK_STREAMING_QUERY" -> args.lift(3)
    ).collect {
      case (name, Some(value)) if value.nonEmpty => name -> value
    }

    StreamingAnalyticsConfig.fromEnvMap(sys.env ++ argEnv)
  }

}
