package chess.tournamentservice

import chess.observability.StructuredLog

final case class TournamentServiceConfig(
    host: String,
    port: Int,
    outputBasePath: String,
    maxParallelJobs: Int,
    analyticsEnabled: Boolean,
    analyticsOutputBasePath: String,
    maxParallelAnalyticsJobs: Int,
    analyticsSbtCommand: List[String],
    analyticsCommand: Option[List[String]] = None,
    analyticsWorkingDir: Option[String]    = None,
    stockfishPath: Option[String],
    searchessAiBaseUrl: Option[String],
    jobStore: String,
    postgresUrl: Option[String],
    postgresUser: Option[String],
    postgresPassword: Option[String],
    postgresSchema: String,
    outboxPublisherEnabled: Boolean       = false,
    outboxPollIntervalSeconds: Int        = 5,
    outboxBatchSize: Int                  = 50,
    outboxMaxAttempts: Int                = 10,
    outboxPublisherType: String           = "logging",
    kafkaBootstrapServers: Option[String] = None,
    kafkaTopic: String                    = "searchess.tournament.lifecycle.v1",
    kafkaClientId: String                 = "searchess-tournament-service",
    kafkaAcks: String                     = "all"
)

object TournamentServiceConfig:

  def loadOrExit(): TournamentServiceConfig =
    load().fold(
      err => {
        StructuredLog.error("tournament-service", "configuration_error", "error" -> err)
        sys.exit(1)
      },
      identity
    )

  def load(
      env: String => Option[String] = key => Option(System.getenv(key)).filter(_.nonEmpty),
      osName: String = System.getProperty("os.name", "")
  ): Either[String, TournamentServiceConfig] =
    for
      port            <- parsePort("TOURNAMENT_HTTP_PORT", env("TOURNAMENT_HTTP_PORT").getOrElse("8085"))
      outputBasePath  <- nonEmpty("TOURNAMENT_OUTPUT_BASE_PATH", env("TOURNAMENT_OUTPUT_BASE_PATH").getOrElse("target/arena/tournament-jobs"))
      maxParallelJobs <- parsePositiveInt("TOURNAMENT_MAX_PARALLEL_JOBS", env("TOURNAMENT_MAX_PARALLEL_JOBS").getOrElse("1"))
      analyticsEnabled <- parseBoolean("TOURNAMENT_ANALYTICS_ENABLED", env("TOURNAMENT_ANALYTICS_ENABLED").getOrElse("true"))
      analyticsOutputBasePath <- nonEmpty(
        "TOURNAMENT_ANALYTICS_OUTPUT_BASE_PATH",
        env("TOURNAMENT_ANALYTICS_OUTPUT_BASE_PATH").getOrElse("target/spark-analytics/tournament-jobs")
      )
      maxParallelAnalyticsJobs <- parsePositiveInt(
        "TOURNAMENT_MAX_PARALLEL_ANALYTICS_JOBS",
        env("TOURNAMENT_MAX_PARALLEL_ANALYTICS_JOBS").getOrElse("1")
      )
      analyticsSbtCommand <- parseCommand(
        "TOURNAMENT_ANALYTICS_SBT_COMMAND",
        env("TOURNAMENT_ANALYTICS_SBT_COMMAND").getOrElse(defaultSbtCommand(osName).mkString(" "))
      )
      analyticsCommand <- parseOptionalCommand("TOURNAMENT_ANALYTICS_COMMAND", env("TOURNAMENT_ANALYTICS_COMMAND"))
      jobStore         <- parseJobStore("TOURNAMENT_JOB_STORE", env("TOURNAMENT_JOB_STORE").getOrElse("memory"))
      postgresUrl      <- requireIfPostgres("TOURNAMENT_POSTGRES_URL", jobStore, env("TOURNAMENT_POSTGRES_URL"))
      postgresUser     <- requireIfPostgres("TOURNAMENT_POSTGRES_USER", jobStore, env("TOURNAMENT_POSTGRES_USER"))
      postgresPassword <- requireIfPostgres("TOURNAMENT_POSTGRES_PASSWORD", jobStore, env("TOURNAMENT_POSTGRES_PASSWORD"))
      postgresSchema   <- parseSchemaName("TOURNAMENT_POSTGRES_SCHEMA", env("TOURNAMENT_POSTGRES_SCHEMA").getOrElse("public"))
      outboxEnabled    <- parseBoolean(
        "TOURNAMENT_OUTBOX_PUBLISHER_ENABLED",
        env("TOURNAMENT_OUTBOX_PUBLISHER_ENABLED").getOrElse("false")
      )
      outboxInterval   <- parsePositiveInt(
        "TOURNAMENT_OUTBOX_POLL_INTERVAL_SECONDS",
        env("TOURNAMENT_OUTBOX_POLL_INTERVAL_SECONDS").getOrElse("5")
      )
      outboxBatch      <- parsePositiveInt(
        "TOURNAMENT_OUTBOX_BATCH_SIZE",
        env("TOURNAMENT_OUTBOX_BATCH_SIZE").getOrElse("50")
      )
      outboxMaxAttempts <- parsePositiveInt(
        "TOURNAMENT_OUTBOX_MAX_ATTEMPTS",
        env("TOURNAMENT_OUTBOX_MAX_ATTEMPTS").getOrElse("10")
      )
      outboxPublisherType  <- parsePublisherType(
        "TOURNAMENT_OUTBOX_PUBLISHER_TYPE",
        env("TOURNAMENT_OUTBOX_PUBLISHER_TYPE").getOrElse("logging")
      )
      kafkaBootstrapServers <- requireIfKafka(
        "TOURNAMENT_KAFKA_BOOTSTRAP_SERVERS",
        outboxPublisherType,
        env("TOURNAMENT_KAFKA_BOOTSTRAP_SERVERS")
      )
      kafkaTopic  <- nonEmpty("TOURNAMENT_KAFKA_TOPIC", env("TOURNAMENT_KAFKA_TOPIC").getOrElse("searchess.tournament.lifecycle.v1"))
      kafkaClientId <- nonEmpty("TOURNAMENT_KAFKA_CLIENT_ID", env("TOURNAMENT_KAFKA_CLIENT_ID").getOrElse("searchess-tournament-service"))
      kafkaAcks     <- nonEmpty("TOURNAMENT_KAFKA_ACKS", env("TOURNAMENT_KAFKA_ACKS").getOrElse("all"))
    yield TournamentServiceConfig(
      host                     = env("TOURNAMENT_HTTP_HOST").getOrElse("0.0.0.0"),
      port                     = port,
      outputBasePath           = outputBasePath,
      maxParallelJobs          = maxParallelJobs,
      analyticsEnabled         = analyticsEnabled,
      analyticsOutputBasePath  = analyticsOutputBasePath,
      maxParallelAnalyticsJobs = maxParallelAnalyticsJobs,
      analyticsSbtCommand      = analyticsSbtCommand,
      analyticsCommand         = analyticsCommand,
      analyticsWorkingDir      = env("TOURNAMENT_ANALYTICS_WORKING_DIR"),
      stockfishPath            = env("STOCKFISH_PATH"),
      searchessAiBaseUrl       = env("SEARCHESS_AI_BASE_URL"),
      jobStore                 = jobStore,
      postgresUrl              = postgresUrl,
      postgresUser             = postgresUser,
      postgresPassword         = postgresPassword,
      postgresSchema           = postgresSchema,
      outboxPublisherEnabled   = outboxEnabled,
      outboxPollIntervalSeconds = outboxInterval,
      outboxBatchSize          = outboxBatch,
      outboxMaxAttempts        = outboxMaxAttempts,
      outboxPublisherType      = outboxPublisherType,
      kafkaBootstrapServers    = kafkaBootstrapServers,
      kafkaTopic               = kafkaTopic,
      kafkaClientId            = kafkaClientId,
      kafkaAcks                = kafkaAcks
    )

  private def requireIfPostgres(name: String, jobStore: String, value: Option[String]): Either[String, Option[String]] =
    if jobStore == "postgres" then
      nonEmpty(name, value.getOrElse("")).map(Some(_))
    else
      Right(value)

  private def requireIfKafka(name: String, publisherType: String, value: Option[String]): Either[String, Option[String]] =
    if publisherType == "kafka" then
      nonEmpty(name, value.getOrElse("")).map(Some(_))
    else
      Right(value)

  private def nonEmpty(name: String, value: String): Either[String, String] =
    val trimmed = value.trim
    if trimmed.nonEmpty then Right(trimmed)
    else Left(s"$name must be non-empty")

  private def parsePort(name: String, value: String): Either[String, Int] =
    value.toIntOption match
      case Some(p) if p >= 1 && p <= 65535 => Right(p)
      case Some(p)                         => Left(s"$name must be between 1 and 65535, got: $p")
      case None                            => Left(s"$name must be an integer, got: '$value'")

  private def parsePositiveInt(name: String, value: String): Either[String, Int] =
    value.toIntOption match
      case Some(p) if p > 0 => Right(p)
      case Some(p)          => Left(s"$name must be positive, got: $p")
      case None             => Left(s"$name must be an integer, got: '$value'")

  private def parseBoolean(name: String, value: String): Either[String, Boolean] =
    value.trim.toLowerCase match
      case "true"  => Right(true)
      case "false" => Right(false)
      case other   => Left(s"$name must be true or false, got: '$other'")

  private[tournamentservice] def parseJobStore(name: String, value: String): Either[String, String] =
    value.trim.toLowerCase match
      case "memory"   => Right("memory")
      case "postgres" => Right("postgres")
      case other      => Left(s"$name must be 'memory' or 'postgres', got: '$other'")

  private[tournamentservice] def parsePublisherType(name: String, value: String): Either[String, String] =
    value.trim.toLowerCase match
      case "logging" => Right("logging")
      case "noop"    => Right("noop")
      case "kafka"   => Right("kafka")
      case other     => Left(s"$name must be 'logging', 'noop', or 'kafka', got: '$other'")

  private[tournamentservice] def parseSchemaName(name: String, value: String): Either[String, String] =
    val trimmed = value.trim
    if trimmed.matches("[a-zA-Z_][a-zA-Z0-9_]*") then Right(trimmed)
    else Left(s"$name must be a valid PostgreSQL schema name (letters, digits, underscore), got: '$trimmed'")

  private[tournamentservice] def defaultSbtCommand(osName: String): List[String] =
    if osName.toLowerCase.contains("windows") then List("cmd.exe", "/c", "sbt", "--client=false")
    else List("sbt", "--client=false")

  private[tournamentservice] def parseCommand(name: String, value: String): Either[String, List[String]] =
    val trimmed = value.trim
    if trimmed.isEmpty then Left(s"$name must be non-empty")
    else
      val tokens = splitCommand(trimmed)
      if tokens.nonEmpty then Right(tokens)
      else Left(s"$name must contain at least one command token")

  private[tournamentservice] def parseOptionalCommand(name: String, value: Option[String]): Either[String, Option[List[String]]] =
    value match
      case None        => Right(None)
      case Some(value) => parseCommand(name, value).map(Some(_))

  private def splitCommand(value: String): List[String] =
    val tokens = scala.collection.mutable.ListBuffer.empty[String]
    val current = StringBuilder()
    var quoted = false
    var quoteChar = ' '

    value.foreach { ch =>
      if quoted then
        if ch == quoteChar then quoted = false
        else current.append(ch)
      else if ch == '"' || ch == '\'' then
        quoted = true
        quoteChar = ch
      else if ch.isWhitespace then
        if current.nonEmpty then
          tokens += current.toString
          current.clear()
      else current.append(ch)
    }

    if current.nonEmpty then tokens += current.toString
    tokens.toList
