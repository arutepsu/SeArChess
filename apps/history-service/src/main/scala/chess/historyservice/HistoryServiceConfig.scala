package chess.historyservice

import chess.observability.StructuredLog

enum HistoryDeliveryMode:
  case Http, RedisStream

final case class HistoryServiceConfig(
    host: String,
    port: Int,
    gameServiceBaseUrl: String,
    postgresUrl: String,
    postgresUser: String,
    postgresPassword: String,
    postgresSchema: Option[String],
    timeoutMillis: Int,
    acceptLegacyIngestionPath: Boolean,
    deliveryMode: HistoryDeliveryMode = HistoryDeliveryMode.Http,
    redisHost: Option[String] = None,
    redisPort: Int = 6379
)

object HistoryServiceConfig:
  def loadOrExit(): HistoryServiceConfig =
    load().fold(
      err => {
        StructuredLog.error("history-service", "configuration_error", "error" -> err)
        sys.exit(1)
      },
      identity
    )

  def load(
      env: String => Option[String] = key => Option(System.getenv(key)).filter(_.nonEmpty)
  ): Either[String, HistoryServiceConfig] =
    for
      port <- parsePort("HISTORY_HTTP_PORT", env("HISTORY_HTTP_PORT").getOrElse("8081"))
      timeout <- parsePositiveInt(
        "HISTORY_GAME_SERVICE_TIMEOUT_MILLIS",
        env("HISTORY_GAME_SERVICE_TIMEOUT_MILLIS").getOrElse("2000")
      )
      legacy <- parseBool(
        "HISTORY_ACCEPT_LEGACY_INGESTION_PATH",
        env("HISTORY_ACCEPT_LEGACY_INGESTION_PATH").getOrElse("false")
      )
      deliveryMode <- parseDeliveryMode(
        "HISTORY_DELIVERY_MODE",
        env("HISTORY_DELIVERY_MODE").getOrElse("http")
      )
      redisPort   <- parsePort("REDIS_PORT", env("REDIS_PORT").getOrElse("6379"))
      postgresUrl <- env("HISTORY_POSTGRES_URL").toRight("HISTORY_POSTGRES_URL is required")
      baseUrl = env("GAME_SERVICE_BASE_URL").getOrElse("http://127.0.0.1:8080")
    yield HistoryServiceConfig(
      host                      = env("HISTORY_HTTP_HOST").getOrElse("0.0.0.0"),
      port                      = port,
      gameServiceBaseUrl        = baseUrl,
      postgresUrl               = postgresUrl,
      postgresUser              = env("HISTORY_POSTGRES_USER").getOrElse("searchess"),
      postgresPassword          = env("HISTORY_POSTGRES_PASSWORD").getOrElse(""),
      postgresSchema            = env("HISTORY_POSTGRES_SCHEMA"),
      timeoutMillis             = timeout,
      acceptLegacyIngestionPath = legacy,
      deliveryMode              = deliveryMode,
      redisHost                 = env("REDIS_HOST"),
      redisPort                 = redisPort
    )

  private def parseDeliveryMode(name: String, value: String): Either[String, HistoryDeliveryMode] =
    value.trim.toLowerCase match
      case "http"                         => Right(HistoryDeliveryMode.Http)
      case "redis-stream" | "redisstream" => Right(HistoryDeliveryMode.RedisStream)
      case other                          => Left(s"$name must be 'http' or 'redis-stream', got: '$other'")

  private def parsePort(name: String, value: String): Either[String, Int] =
    value.toIntOption match
      case Some(p) if p >= 1 && p <= 65535 => Right(p)
      case Some(p)                          => Left(s"$name must be between 1 and 65535, got: $p")
      case None                             => Left(s"$name must be an integer, got: '$value'")

  private def parsePositiveInt(name: String, value: String): Either[String, Int] =
    value.toIntOption match
      case Some(n) if n >= 1 => Right(n)
      case Some(n)           => Left(s"$name must be >= 1, got: $n")
      case None              => Left(s"$name must be an integer, got: '$value'")

  private def parseBool(name: String, value: String): Either[String, Boolean] =
    value.trim.toLowerCase match
      case "true"  => Right(true)
      case "false" => Right(false)
      case other   => Left(s"$name must be true or false, got: '$other'")
