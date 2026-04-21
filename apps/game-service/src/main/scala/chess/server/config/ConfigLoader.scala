package chess.server.config

import chess.observability.StructuredLog

/** Loads Game Service runtime configuration from environment variables. */
object ConfigLoader:

  private val DefaultHttpHost: String = "0.0.0.0"
  private val DefaultHttpPort: String = "8080"
  private val DefaultWsEnabled: String = "true"
  private val DefaultWsPort: String = "9090"
  private val DefaultPersistence: String = "in-memory"
  private val DefaultSqlitePath: String = "chess.db"
  private val DefaultEventMode: String = "in-process"
  private val DefaultCorsEnabled: String = "false"
  private val DefaultCorsAllowOrigin: String = "*"
  private val DefaultHistoryEnabled: String = "false"
  private val DefaultHistoryTimeout: String = "2000"
  private val DefaultAiMode: String = "remote"
  private val DefaultAiRemoteBaseUrl: String = "http://ai-service:8765"
  private val DefaultAiTimeoutMillis: String = "2000"

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
      history <- parseHistoryForwardingConfig(
        histEnabled,
        env("HISTORY_SERVICE_BASE_URL"),
        histTimeout
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
    yield AppConfig(
      http = HttpConfig(httpHost, httpPort),
      webSocket = WebSocketConfig(wsEnabled, wsPort),
      persistence = persistence,
      sqlite =
        if persistence == PersistenceMode.SQLite then Some(SqliteConfig(sqlitePath)) else None,
      eventMode = eventMode,
      cors = CorsConfig(corsEnabled, corsOrigin),
      history = history,
      ai = AiConfig(
        mode = aiMode,
        remote = remote,
        timeoutMillis = aiTimeout,
        defaultEngineId = engineId
      )
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
      case "in-memory" | "inmemory" => Right(PersistenceMode.InMemory)
      case "sqlite"                 => Right(PersistenceMode.SQLite)
      case _ => Left(s"PERSISTENCE_MODE must be 'in-memory' or 'sqlite', got: '$value'")

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

  private def parseHistoryForwardingConfig(
      enabled: Boolean,
      baseUrl: Option[String],
      timeoutMillis: Int
  ): Either[String, HistoryForwardingConfig] =
    if enabled then
      baseUrl.map(_.trim).filter(_.nonEmpty) match
        case Some(url) => Right(HistoryForwardingConfig(true, Some(url), timeoutMillis))
        case None =>
          Left("HISTORY_SERVICE_BASE_URL is required when HISTORY_FORWARDING_ENABLED is true")
    else
      Right(HistoryForwardingConfig(false, baseUrl.map(_.trim).filter(_.nonEmpty), timeoutMillis))
