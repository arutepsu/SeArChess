package chess.historyservice

<<<<<<< HEAD
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
    redisUrl: Option[String] = None,
    redisHost: Option[String] = None,
    redisPort: Int = 6379,
    redisStream: String = "searchess.history.archives",
    redisGroup: String = "history-service",
    redisConsumerName: String = defaultConsumerName()
<<<<<<< HEAD
=======
final case class HistoryServiceConfig(
<<<<<<< HEAD
  host:               String,
  port:               Int,
  gameServiceBaseUrl: String,
  dbPath:             String,
  timeoutMillis:      Int
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services)
=======
  host:                      String,
  port:                      Int,
  gameServiceBaseUrl:        String,
  dbPath:                    String,
  timeoutMillis:             Int,
  acceptLegacyIngestionPath: Boolean
>>>>>>> ce08c01e (local microservices)
=======
>>>>>>> 966317ea (added bot container)
)

object HistoryServiceConfig:
  def loadOrExit(): HistoryServiceConfig =
    load().fold(
<<<<<<< HEAD
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
        env("HISTORY_INGESTION_MODE").map(_ -> "HISTORY_INGESTION_MODE").orElse(
          env("HISTORY_DELIVERY_MODE").map(_ -> "HISTORY_DELIVERY_MODE")
        ).getOrElse("http" -> "HISTORY_INGESTION_MODE")
      )
      redisEndpoint <- parseRedisEndpoint(
        env("HISTORY_REDIS_URL"),
        env("REDIS_HOST"),
        env("REDIS_PORT").getOrElse("6379")
      )
      redisStream = env("HISTORY_REDIS_STREAM").getOrElse("searchess.history.archives").trim
      redisGroup = env("HISTORY_REDIS_GROUP").getOrElse("history-service").trim
      redisConsumer = env("HISTORY_REDIS_CONSUMER_NAME").getOrElse(defaultConsumerName()).trim
      _ <- validateRedisConfig(deliveryMode, redisEndpoint, redisStream, redisGroup, redisConsumer)
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
      redisUrl                  = redisEndpoint.map(_.url),
      redisHost                 = redisEndpoint.map(_.host),
      redisPort                 = redisEndpoint.map(_.port).getOrElse(6379),
      redisStream               = redisStream,
      redisGroup                = redisGroup,
      redisConsumerName         = redisConsumer
<<<<<<< HEAD
    )

  private def parseDeliveryMode(raw: (String, String)): Either[String, HistoryDeliveryMode] =
    val (value, name) = raw
    value.trim.toLowerCase match
      case "http"                         => Right(HistoryDeliveryMode.Http)
      case "redis-stream" | "redisstream" => Right(HistoryDeliveryMode.RedisStream)
      case other                          => Left(s"$name must be 'http' or 'redis-stream', got: '$other'")

  private final case class RedisEndpoint(url: String, host: String, port: Int)

  private def parseRedisEndpoint(
      url: Option[String],
      host: Option[String],
      rawPort: String
  ): Either[String, Option[RedisEndpoint]] =
    url.map(_.trim).filter(_.nonEmpty) match
      case Some(value) =>
        try
          val uri  = java.net.URI(value)
          val port = if uri.getPort == -1 then 6379 else uri.getPort
          Option(uri.getHost).filter(_.nonEmpty) match
            case Some(h) => Right(Some(RedisEndpoint(value, h, port)))
            case None    => Left(s"HISTORY_REDIS_URL must include a host, got: '$value'")
        catch case _: java.net.URISyntaxException => Left(s"HISTORY_REDIS_URL is invalid: '$value'")
      case None =>
        host.map(_.trim).filter(_.nonEmpty) match
          case Some(h) => parsePort("REDIS_PORT", rawPort).map(p => Some(RedisEndpoint(s"redis://$h:$p", h, p)))
          case None    => Right(None)

  private def validateRedisConfig(
      mode: HistoryDeliveryMode,
      endpoint: Option[RedisEndpoint],
      stream: String,
      group: String,
      consumer: String
  ): Either[String, Unit] =
    mode match
      case HistoryDeliveryMode.Http => Right(())
      case HistoryDeliveryMode.RedisStream =>
        if endpoint.isEmpty then Left("HISTORY_REDIS_URL or REDIS_HOST is required when HISTORY_INGESTION_MODE=redis-stream")
        else if stream.isEmpty then Left("HISTORY_REDIS_STREAM is required when HISTORY_INGESTION_MODE=redis-stream")
        else if group.isEmpty then Left("HISTORY_REDIS_GROUP is required when HISTORY_INGESTION_MODE=redis-stream")
        else if consumer.isEmpty then Left("HISTORY_REDIS_CONSUMER_NAME must not be blank when HISTORY_INGESTION_MODE=redis-stream")
        else Right(())

  private def defaultConsumerName(): String =
    Option(System.getenv("HOSTNAME")).map(_.trim).filter(_.nonEmpty).getOrElse("history-service-1")

  private def parsePort(name: String, value: String): Either[String, Int] =
    value.toIntOption match
      case Some(p) if p >= 1 && p <= 65535 => Right(p)
      case Some(p)                          => Left(s"$name must be between 1 and 65535, got: $p")
      case None                             => Left(s"$name must be an integer, got: '$value'")
=======
      err => { System.err.println(s"[history] Configuration error: $err"); sys.exit(1) },
      identity
    )

  def load(env: String => Option[String] = key => Option(System.getenv(key)).filter(_.nonEmpty)): Either[String, HistoryServiceConfig] =
    for
      port    <- parsePort("HISTORY_HTTP_PORT", env("HISTORY_HTTP_PORT").getOrElse("8081"))
      timeout <- parsePositiveInt("HISTORY_GAME_SERVICE_TIMEOUT_MILLIS", env("HISTORY_GAME_SERVICE_TIMEOUT_MILLIS").getOrElse("2000"))
      legacy <- parseBool(
        "HISTORY_ACCEPT_LEGACY_INGESTION_PATH",
        env("HISTORY_ACCEPT_LEGACY_INGESTION_PATH").getOrElse("false")
      )
      baseUrl = env("GAME_SERVICE_BASE_URL").getOrElse("http://127.0.0.1:8080")
      dbPath  = env("HISTORY_DB_PATH").getOrElse("history.sqlite")
    yield HistoryServiceConfig(
      host                      = env("HISTORY_HTTP_HOST").getOrElse("0.0.0.0"),
      port                      = port,
      gameServiceBaseUrl        = baseUrl,
      dbPath                    = dbPath,
      timeoutMillis             = timeout,
      acceptLegacyIngestionPath = legacy
=======
>>>>>>> 966317ea (added bot container)
    )

  private def parseDeliveryMode(raw: (String, String)): Either[String, HistoryDeliveryMode] =
    val (value, name) = raw
    value.trim.toLowerCase match
      case "http"                         => Right(HistoryDeliveryMode.Http)
      case "redis-stream" | "redisstream" => Right(HistoryDeliveryMode.RedisStream)
      case other                          => Left(s"$name must be 'http' or 'redis-stream', got: '$other'")

  private final case class RedisEndpoint(url: String, host: String, port: Int)

  private def parseRedisEndpoint(
      url: Option[String],
      host: Option[String],
      rawPort: String
  ): Either[String, Option[RedisEndpoint]] =
    url.map(_.trim).filter(_.nonEmpty) match
      case Some(value) =>
        try
          val uri  = java.net.URI(value)
          val port = if uri.getPort == -1 then 6379 else uri.getPort
          Option(uri.getHost).filter(_.nonEmpty) match
            case Some(h) => Right(Some(RedisEndpoint(value, h, port)))
            case None    => Left(s"HISTORY_REDIS_URL must include a host, got: '$value'")
        catch case _: java.net.URISyntaxException => Left(s"HISTORY_REDIS_URL is invalid: '$value'")
      case None =>
        host.map(_.trim).filter(_.nonEmpty) match
          case Some(h) => parsePort("REDIS_PORT", rawPort).map(p => Some(RedisEndpoint(s"redis://$h:$p", h, p)))
          case None    => Right(None)

  private def validateRedisConfig(
      mode: HistoryDeliveryMode,
      endpoint: Option[RedisEndpoint],
      stream: String,
      group: String,
      consumer: String
  ): Either[String, Unit] =
    mode match
      case HistoryDeliveryMode.Http => Right(())
      case HistoryDeliveryMode.RedisStream =>
        if endpoint.isEmpty then Left("HISTORY_REDIS_URL or REDIS_HOST is required when HISTORY_INGESTION_MODE=redis-stream")
        else if stream.isEmpty then Left("HISTORY_REDIS_STREAM is required when HISTORY_INGESTION_MODE=redis-stream")
        else if group.isEmpty then Left("HISTORY_REDIS_GROUP is required when HISTORY_INGESTION_MODE=redis-stream")
        else if consumer.isEmpty then Left("HISTORY_REDIS_CONSUMER_NAME must not be blank when HISTORY_INGESTION_MODE=redis-stream")
        else Right(())

  private def defaultConsumerName(): String =
    Option(System.getenv("HOSTNAME")).map(_.trim).filter(_.nonEmpty).getOrElse("history-service-1")

  private def parsePort(name: String, value: String): Either[String, Int] =
    value.toIntOption match
      case Some(p) if p >= 1 && p <= 65535 => Right(p)
<<<<<<< HEAD
      case Some(p) => Left(s"$name must be between 1 and 65535, got: $p")
      case None => Left(s"$name must be an integer, got: '$value'")
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services)
=======
      case Some(p)                          => Left(s"$name must be between 1 and 65535, got: $p")
      case None                             => Left(s"$name must be an integer, got: '$value'")
>>>>>>> 966317ea (added bot container)

  private def parsePositiveInt(name: String, value: String): Either[String, Int] =
    value.toIntOption match
      case Some(n) if n >= 1 => Right(n)
<<<<<<< HEAD
      case Some(n)           => Left(s"$name must be >= 1, got: $n")
      case None              => Left(s"$name must be an integer, got: '$value'")

  private def parseBool(name: String, value: String): Either[String, Boolean] =
    value.trim.toLowerCase match
      case "true"  => Right(true)
      case "false" => Right(false)
      case other   => Left(s"$name must be true or false, got: '$other'")
=======
      case Some(n) => Left(s"$name must be >= 1, got: $n")
      case None => Left(s"$name must be an integer, got: '$value'")
<<<<<<< HEAD
>>>>>>> 5e4d1e43 (game and history services. add docker, isolate services)
=======

  private def parseBool(name: String, value: String): Either[String, Boolean] =
    value.trim.toLowerCase match
      case "true"  => Right(true)
      case "false" => Right(false)
      case other   => Left(s"$name must be true or false, got: '$other'")
>>>>>>> ce08c01e (local microservices)
