package chess.analytics.config

final case class LiveGameAnalyticsConfig(
  bootstrapServers: String,
  topic:            String,
  checkpointDir:    String,
  startingOffsets:  String,
  triggerSeconds:   Int,
  postgres:         PostgresConfig
)

object LiveGameAnalyticsConfig {

  private val allowedStartingOffsets = Set("latest", "earliest")

  def fromEnv(): LiveGameAnalyticsConfig = fromEnvMap(sys.env)

  private[analytics] def fromEnvMap(env: Map[String, String]): LiveGameAnalyticsConfig = {
    val topic = env
      .get("KAFKA_GAME_EVENTS_TOPIC")
      .orElse(env.get("KAFKA_TOPIC"))
      .getOrElse("searchess.game.events.v1")

    val startingOffsets = env.getOrElse("SPARK_STREAMING_STARTING_OFFSETS", "latest").toLowerCase
    require(allowedStartingOffsets.contains(startingOffsets),
      s"SPARK_STREAMING_STARTING_OFFSETS must be one of: ${allowedStartingOffsets.mkString(", ")}")

    val triggerSeconds = try {
      env.getOrElse("SPARK_STREAMING_TRIGGER_SECONDS", "10").toInt
    } catch {
      case _: NumberFormatException =>
        throw new IllegalArgumentException("SPARK_STREAMING_TRIGGER_SECONDS must be a positive integer")
    }
    require(triggerSeconds > 0, "SPARK_STREAMING_TRIGGER_SECONDS must be positive")

    val postgres = PostgresConfig.fromEnvMap(env).getOrElse(
      throw new IllegalStateException(
        "Postgres is required for live game analytics. " +
        "Set POSTGRES_WRITE_ENABLED=true and POSTGRES_URL, POSTGRES_USER, POSTGRES_PASSWORD."
      )
    )

    LiveGameAnalyticsConfig(
      bootstrapServers = env.getOrElse("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
      topic            = topic,
      checkpointDir    = env.getOrElse("SPARK_CHECKPOINT_DIR", "/spark-checkpoints/live-game-events"),
      startingOffsets  = startingOffsets,
      triggerSeconds   = triggerSeconds,
      postgres         = postgres
    )
  }
}
