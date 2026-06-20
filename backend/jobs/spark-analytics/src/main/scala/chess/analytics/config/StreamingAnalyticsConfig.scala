package chess.analytics.config

final case class StreamingAnalyticsConfig(
  bootstrapServers: String = "localhost:9092",
  topic:            String = "game-events",
  checkpointDir:    String = "target/spark-checkpoints/game-analytics",
  startingOffsets:  String = "latest",
  triggerSeconds:   Int    = 5,
  query:            String = "leaderboard"
) {
  def checkpointLocation(queryName: String): String =
    s"${checkpointDir.stripSuffix("/")}/$queryName"
}

object StreamingAnalyticsConfig {

  val allowedStartingOffsets: Set[String] = Set("latest", "earliest")

  val allowedQueries: Set[String] = Set(
    "leaderboard",
    "terminations",
    "bot-families",
    "games-per-minute",
    "avg-duration-window",
    "win-rate-window",
    "move-throughput"
  )

  def fromEnv(): StreamingAnalyticsConfig = fromEnvMap(sys.env)

  private[analytics] def fromEnvMap(env: Map[String, String]): StreamingAnalyticsConfig = {
    val topic = env
      .get("KAFKA_TOPIC")
      .orElse(env.get("KAFKA_GAME_EVENTS_TOPIC"))
      .getOrElse("game-events")

    val config = StreamingAnalyticsConfig(
      bootstrapServers = env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
      topic            = topic,
      checkpointDir    = env.getOrElse("SPARK_CHECKPOINT_DIR", "target/spark-checkpoints/game-analytics"),
      startingOffsets  = env.getOrElse("SPARK_STREAMING_STARTING_OFFSETS", "latest").toLowerCase,
      triggerSeconds   = parseTriggerSeconds(env.getOrElse("SPARK_STREAMING_TRIGGER_SECONDS", "5")),
      query            = env.getOrElse("SPARK_STREAMING_QUERY", "leaderboard").toLowerCase
    )

    validate(config)
    config
  }

  private def parseTriggerSeconds(value: String): Int =
    try {
      value.toInt
    } catch {
      case _: NumberFormatException =>
        throw new IllegalArgumentException("SPARK_STREAMING_TRIGGER_SECONDS must be a positive integer")
    }

  private def validate(config: StreamingAnalyticsConfig): Unit = {
    require(config.topic.trim.nonEmpty, "KAFKA_TOPIC must be non-empty")
    require(config.checkpointDir.trim.nonEmpty, "SPARK_CHECKPOINT_DIR must be non-empty")
    require(
      allowedStartingOffsets.contains(config.startingOffsets),
      s"SPARK_STREAMING_STARTING_OFFSETS must be one of: ${allowedStartingOffsets.toSeq.sorted.mkString(", ")}"
    )
    require(config.triggerSeconds > 0, "SPARK_STREAMING_TRIGGER_SECONDS must be positive")
    require(
      allowedQueries.contains(config.query),
      s"SPARK_STREAMING_QUERY must be one of: ${allowedQueries.toSeq.sorted.mkString(", ")}"
    )
  }
}
