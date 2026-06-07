package chess.server.config

import chess.observability.StructuredLog

/** Loads Game Service runtime configuration from environment variables. */
object ConfigLoader:

  private val DefaultHttpHost: String = "0.0.0.0"
  private val DefaultHttpPort: String = "8080"
  private val DefaultWsEnabled: String = "true"
  private val DefaultWsPort: String = "9090"
  private val DefaultPersistence: String = "postgres"
  private val DefaultSqlitePath: String = "chess.db"
  private val DefaultEventMode: String = "in-process"
  private val DefaultCorsEnabled: String = "false"
  private val DefaultCorsAllowOrigin: String = "*"
  private val DefaultHistoryEnabled: String      = "false"
  private val DefaultHistoryTimeout: String      = "2000"
  private val DefaultHistoryDeliveryMode: String = "http"
  private val DefaultRedisPort: String           = "6379"
  private val DefaultHistoryRedisStream: String  = "searchess.history.archives"
  private val DefaultAiMode: String = "remote"
  private val DefaultAiRemoteBaseUrl: String = "http://ai-service:8765"
  private val DefaultAiTimeoutMillis: String = "15000"
  private val DefaultMigrationAdminEnabled: String = "false"
  private val DefaultExternalGameBotPlatform: String = "Lichess"
  private val DefaultExternalGameBotActorId: String = "searchess-bot"
  private val DefaultUserServiceBaseUrl: String = "http://user-service:8082"
  private val DefaultUserServiceTimeout: String = "2000"

  def load(): Either[String, AppConfig] =
    loadFrom(key => Option(System.getenv(key)).filter(_.nonEmpty))

  private[config] def loadFrom(env: String => Option[String]): Either[String, AppConfig] =
    for
      httpHost <- Right(env("HTTP_HOST").getOrElse(DefaultHttpHost))
      httpPort <- parsePort("HTTP_PORT", env("HTTP_PORT").getOrElse(DefaultHttpPort))
      wsEnabled <- parseBool("WS_ENABLED", env("WS_ENABLED").getOrElse(DefaultWsEnabled))
      wsPort <- parsePort("WS_PORT", env("WS_PORT").getOrElse(DefaultWsPort))
      persistence <- parsePersistenceMode(env("PERSISTENCE_MODE").getOrElse(DefaultPersistence))
      sqlitePath = env("CHESS_DB_PATH").getOrElse(DefaultSqlitePath)
      postgres <- loadPostgresConfigIfNeeded(persistence, env)
      mongo <- loadMongoConfigIfNeeded(persistence, env)
      eventMode <- parseEventMode(env("EVENT_MODE").getOrElse(DefaultEventMode))
      corsEnabled <- parseBool("CORS_ENABLED", env("CORS_ENABLED").getOrElse(DefaultCorsEnabled))
      corsOrigin = env("CORS_ALLOWED_ORIGIN").getOrElse(DefaultCorsAllowOrigin)
      histEnabled <- parseBool(
        "HISTORY_FORWARDING_ENABLED",
        env("HISTORY_FORWARDING_ENABLED").getOrElse(DefaultHistoryEnabled)
      )
      histTimeout <- parsePositiveInt(
        "HISTORY_FORWARDING_TIMEOUT_MILLIS",
        env("HISTORY_FORWARDING_TIMEOUT_MILLIS").getOrElse(DefaultHistoryTimeout)
      )
      histDeliveryMode <- parseHistoryDeliveryMode(
        env("HISTORY_DELIVERY_MODE").getOrElse(DefaultHistoryDeliveryMode)
      )
      redisEndpoint <- parseRedisEndpoint(
        env("HISTORY_REDIS_URL"),
        env("REDIS_HOST"),
        env("REDIS_PORT").getOrElse(DefaultRedisPort)
      )
      redisStream = env("HISTORY_REDIS_STREAM").getOrElse(DefaultHistoryRedisStream)
      history <- parseHistoryForwardingConfig(
        histEnabled,
        env("HISTORY_SERVICE_BASE_URL"),
        histTimeout,
        histDeliveryMode,
        redisEndpoint,
        redisStream
      )
      aiMode <- parseAiProviderMode(env("AI_PROVIDER_MODE").getOrElse(DefaultAiMode))
      aiTimeout <- parsePositiveInt(
        "AI_TIMEOUT_MILLIS",
        env("AI_TIMEOUT_MILLIS").getOrElse(DefaultAiTimeoutMillis)
      )
      remoteUrl = env("AI_REMOTE_BASE_URL").orElse(
        if aiMode == AiProviderMode.Remote then Some(DefaultAiRemoteBaseUrl) else None
      )
      remote <- parseRemoteAiConfig(aiMode, remoteUrl, env("AI_REMOTE_TEST_MODE"))
      engineId = env("AI_DEFAULT_ENGINE_ID")
      migrationAdmin <- parseBool(
        "MIGRATION_ADMIN_ENABLED",
        env("MIGRATION_ADMIN_ENABLED").getOrElse(DefaultMigrationAdminEnabled)
      )
      migrationToken <- parseMigrationAdminToken(migrationAdmin, env("MIGRATION_ADMIN_TOKEN"))
      userServiceTimeout <- parsePositiveInt(
        "USER_SERVICE_TIMEOUT_MILLIS",
        env("USER_SERVICE_TIMEOUT_MILLIS").getOrElse(DefaultUserServiceTimeout)
      )
      userServiceBaseUrl = env("USER_SERVICE_BASE_URL").getOrElse(DefaultUserServiceBaseUrl).trim
      _ <- Either.cond(userServiceBaseUrl.nonEmpty, (), "USER_SERVICE_BASE_URL must be non-empty")
      externalGameBot <- parseExternalGameBotConfig(
        env("EXTERNAL_GAME_BOT_API_KEY"),
        env("EXTERNAL_GAME_BOT_PLATFORM").getOrElse(DefaultExternalGameBotPlatform),
        env("EXTERNAL_GAME_BOT_ACTOR_ID").getOrElse(DefaultExternalGameBotActorId)
      )
    yield AppConfig(
      http = HttpConfig(httpHost, httpPort),
      webSocket = WebSocketConfig(wsEnabled, wsPort),
      persistence = persistence,
      sqlite =
        if persistence == PersistenceMode.SQLite then Some(SqliteConfig(sqlitePath)) else None,
      postgres = postgres,
      mongo = mongo,
      eventMode = eventMode,
      cors = CorsConfig(corsEnabled, corsOrigin),
      history = history,
      ai = AiConfig(
        mode = aiMode,
        remote = remote,
        timeoutMillis = aiTimeout,
        defaultEngineId = engineId
      ),
      userService = UserServiceConfig(userServiceBaseUrl, userServiceTimeout),
      externalGameBot = externalGameBot,
      migrationAdminEnabled = migrationAdmin,
      migrationAdminToken = migrationToken
    )

  def loadOrExit(): AppConfig =
    load().fold(
      err => {
        StructuredLog.error("game-service", "configuration_error", "error" -> err)
        sys.exit(1)
      },
      identity
    )

  private def parsePort(varName: String, value: String): Either[String, Int] =
    value.toIntOption match
      case None                          => Left(s"$varName must be an integer, got: '$value'")
      case Some(p) if p < 1 || p > 65535 => Left(s"$varName must be between 1 and 65535, got: $p")
      case Some(p)                       => Right(p)

  private def parsePositiveInt(varName: String, value: String): Either[String, Int] =
    value.toIntOption match
      case None             => Left(s"$varName must be an integer, got: '$value'")
      case Some(n) if n < 1 => Left(s"$varName must be >= 1, got: $n")
      case Some(n)          => Right(n)

  private def parseBool(varName: String, value: String): Either[String, Boolean] =
    value.toLowerCase match
      case "true" | "1" | "yes" => Right(true)
      case "false" | "0" | "no" => Right(false)
      case _                    => Left(s"$varName must be true/false/1/0/yes/no, got: '$value'")

  private def parsePersistenceMode(value: String): Either[String, PersistenceMode] =
    value.toLowerCase match
      case "postgres" | "postgresql" => Right(PersistenceMode.Postgres)
      case "mongo" | "mongodb"       => Right(PersistenceMode.Mongo)
      case "in-memory" | "inmemory" => Right(PersistenceMode.InMemory)
      case "sqlite"                 => Right(PersistenceMode.SQLite)
      case _ =>
        Left(
          s"PERSISTENCE_MODE must be 'postgres', 'mongo', 'sqlite', or 'in-memory', got: '$value'"
        )

  private def loadPostgresConfigIfNeeded(
      persistence: PersistenceMode,
      env: String => Option[String]
  ): Either[String, Option[PostgresConfig]] =
    persistence match
      case PersistenceMode.Postgres =>
        PostgresConfigLoader
          .load(
            env,
            requireCredentials = true,
            contextMessage = Some(PostgresConfigLoader.MissingDefaultPostgresMessage)
          )
          .map(Some.apply)
      case PersistenceMode.Mongo | PersistenceMode.InMemory | PersistenceMode.SQLite =>
        Right(None)

  private def loadMongoConfigIfNeeded(
      persistence: PersistenceMode,
      env: String => Option[String]
  ): Either[String, Option[MongoConfig]] =
    persistence match
      case PersistenceMode.Mongo =>
        MongoConfigLoader
          .load(env, contextMessage = Some("Mongo runtime persistence requires SEARCHESS_MONGO_URI."))
          .map(Some.apply)
      case PersistenceMode.Postgres | PersistenceMode.InMemory | PersistenceMode.SQLite =>
        Right(None)

  private def parseEventMode(value: String): Either[String, EventMode] =
    value.toLowerCase match
      case "in-process" | "inprocess" => Right(EventMode.InProcess)
      case _                          => Left(s"EVENT_MODE must be 'in-process', got: '$value'")

  private def parseAiProviderMode(value: String): Either[String, AiProviderMode] =
    value.toLowerCase match
      case "local" | "local-deterministic" | "localdeterministic" =>
        Right(AiProviderMode.LocalDeterministic)
      case "disabled" | "off" | "none" =>
        Right(AiProviderMode.Disabled)
      case "remote" =>
        Right(AiProviderMode.Remote)
      case _ =>
        Left(s"AI_PROVIDER_MODE must be 'remote', 'local', or 'disabled', got: '$value'")

  private def parseRemoteAiConfig(
      mode: AiProviderMode,
      baseUrl: Option[String],
      testMode: Option[String]
  ): Either[String, Option[RemoteAiConfig]] =
    val normalisedTestMode = testMode.map(_.trim).filter(_.nonEmpty)
    mode match
      case AiProviderMode.Remote =>
        baseUrl match
          case Some(url) if url.trim.nonEmpty =>
            Right(Some(RemoteAiConfig(url.trim, normalisedTestMode)))
          case _ => Left("AI_REMOTE_BASE_URL is required when AI_PROVIDER_MODE is 'remote'")
      case _ =>
        Right(
          baseUrl
            .map(url => RemoteAiConfig(url.trim, normalisedTestMode))
            .filter(_.baseUrl.nonEmpty)
        )

  private def parseMigrationAdminToken(
      enabled: Boolean,
      rawToken: Option[String]
  ): Either[String, Option[String]] =
    val token = rawToken.map(_.trim).filter(_.nonEmpty)
    if enabled then
      token match
        case Some(t) => Right(Some(t))
        case None =>
          Left(
            "MIGRATION_ADMIN_TOKEN is required when MIGRATION_ADMIN_ENABLED=true. " +
              "Set a non-empty token to protect the admin migration route."
          )
    else
      Right(token)

  private def parseExternalGameBotConfig(
      rawApiKey: Option[String],
      rawPlatform: String,
      rawActorId: String
  ): Either[String, Option[ExternalGameBotConfig]] =
    rawApiKey.map(_.trim).filter(_.nonEmpty) match
      case None => Right(None)
      case Some(apiKey) =>
        val platform = rawPlatform.trim
        val actorId = rawActorId.trim
        for
          _ <- parseExternalGameBotPlatform(platform)
          _ <- Either.cond(actorId.nonEmpty, (), "EXTERNAL_GAME_BOT_ACTOR_ID must be non-empty when external-game bot credentials are configured")
        yield Some(ExternalGameBotConfig(platform, actorId, apiKey))

  private def parseExternalGameBotPlatform(value: String): Either[String, Unit] =
    value.toLowerCase match
      case "lichess" => Right(())
      case other =>
        Left(s"EXTERNAL_GAME_BOT_PLATFORM must be 'Lichess', got: '$other'")

  private def parseHistoryForwardingConfig(
      enabled: Boolean,
      baseUrl: Option[String],
      timeoutMillis: Int,
      deliveryMode: HistoryDeliveryMode,
      redisEndpoint: Option[RedisEndpoint],
      redisStream: String
  ): Either[String, HistoryForwardingConfig] =
    val cleanUrl = baseUrl.map(_.trim).filter(_.nonEmpty)
    val cleanStream = redisStream.trim
    if !enabled then
      Right(
        HistoryForwardingConfig(
          enabled      = false,
          baseUrl      = cleanUrl,
          timeoutMillis = timeoutMillis,
          deliveryMode = deliveryMode,
          redisUrl     = redisEndpoint.map(_.url),
          redisHost    = redisEndpoint.map(_.host),
          redisPort    = redisEndpoint.map(_.port).getOrElse(DefaultRedisPort.toInt),
          redisStream  = cleanStream
        )
      )
    else
      deliveryMode match
        case HistoryDeliveryMode.RedisStream =>
          redisEndpoint match
            case Some(endpoint) if cleanStream.nonEmpty =>
              Right(
                HistoryForwardingConfig(
                  enabled      = true,
                  baseUrl      = cleanUrl,
                  timeoutMillis = timeoutMillis,
                  deliveryMode = HistoryDeliveryMode.RedisStream,
                  redisUrl     = Some(endpoint.url),
                  redisHost    = Some(endpoint.host),
                  redisPort    = endpoint.port,
                  redisStream  = cleanStream
                )
              )
            case Some(_) =>
              Left("HISTORY_REDIS_STREAM is required when HISTORY_DELIVERY_MODE=redis-stream")
            case None =>
              Left(
                "HISTORY_REDIS_URL or REDIS_HOST is required when HISTORY_FORWARDING_ENABLED=true and HISTORY_DELIVERY_MODE=redis-stream"
              )
        case HistoryDeliveryMode.Http =>
          cleanUrl match
            case Some(url) =>
              Right(
                HistoryForwardingConfig(
                  enabled      = true,
                  baseUrl      = Some(url),
                  timeoutMillis = timeoutMillis,
                  deliveryMode = HistoryDeliveryMode.Http,
                  redisUrl     = redisEndpoint.map(_.url),
                  redisHost    = redisEndpoint.map(_.host),
                  redisPort    = redisEndpoint.map(_.port).getOrElse(DefaultRedisPort.toInt),
                  redisStream  = cleanStream
                )
              )
            case None =>
              Left(
                "HISTORY_SERVICE_BASE_URL is required when HISTORY_FORWARDING_ENABLED=true and HISTORY_DELIVERY_MODE=http"
              )

  private def parseHistoryDeliveryMode(value: String): Either[String, HistoryDeliveryMode] =
    value.trim.toLowerCase match
      case "http"                        => Right(HistoryDeliveryMode.Http)
      case "redis-stream" | "redisstream" => Right(HistoryDeliveryMode.RedisStream)
      case _ =>
        Left(s"HISTORY_DELIVERY_MODE must be 'http' or 'redis-stream', got: '$value'")

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
          case Some(h) =>
            parsePort("REDIS_PORT", rawPort).map(p => Some(RedisEndpoint(s"redis://$h:$p", h, p)))
          case None => Right(None)
