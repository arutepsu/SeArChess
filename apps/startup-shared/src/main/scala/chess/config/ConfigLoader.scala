package chess.config

/** Loads [[AppConfig]] from environment variables with sensible defaults.
 *
 *  App selection (server, GUI, TUI) is determined by the SBT project and
 *  entry point, not by this loader.  These variables control runtime
 *  infrastructure only.
 *
 *  === Supported environment variables ===
 *  {{{
 *    HTTP_HOST         any hostname or IP address  (default: 0.0.0.0)
 *    HTTP_PORT         integer 1–65535             (default: 8080)
 *    WS_ENABLED        true/false/1/0/yes/no       (default: true)
 *    WS_PORT           integer 1–65535             (default: 9090)
 *    PERSISTENCE_MODE  in-memory                   (default: in-memory)
 *    EVENT_MODE        in-process                  (default: in-process)
 *    CORS_ENABLED      true/false/1/0/yes/no       (default: false)
 *    CORS_ALLOWED_ORIGIN  * | specific origin URL  (default: *)
 *    AI_PROVIDER_MODE  local | disabled | remote    (default: local)
 *    AI_REMOTE_BASE_URL  URL for remote AI mode      (required for remote)
 *    AI_TIMEOUT_MILLIS integer >= 1                 (default: 2000)
 *    AI_DEFAULT_ENGINE_ID optional engine id         (default: unset)
 *  }}}
 *
 *  === Validation ===
 *  [[load]] returns `Left(errorMessage)` on the first invalid value found.
 *  [[loadOrExit]] prints the error and terminates the process immediately;
 *  it is intended for use in JVM entry points only.
 */
object ConfigLoader:

  // ── Defaults ─────────────────────────────────────────────────────────────────

  private val DefaultHttpHost:        String = "0.0.0.0"
  private val DefaultHttpPort:        String = "8080"
  private val DefaultWsEnabled:       String = "true"
  private val DefaultWsPort:          String = "9090"
  private val DefaultPersistence:     String = "in-memory"
  private val DefaultEventMode:       String = "in-process"
  private val DefaultCorsEnabled:     String = "false"
  private val DefaultCorsAllowOrigin: String = "*"
  private val DefaultAiMode:          String = "local"
  private val DefaultAiTimeoutMillis: String = "2000"

  // ── Public API ───────────────────────────────────────────────────────────────

  /** Load and validate configuration from the environment.
   *
   *  Returns `Right(config)` when all values are present and valid, or
   *  `Left(message)` describing the first problem encountered.
   */
  def load(): Either[String, AppConfig] =
    loadFrom(key => Option(System.getenv(key)).filter(_.nonEmpty))

  /** Load and validate configuration from a supplied environment lookup.
   *
   *  Kept package-visible for focused tests without mutating the process
   *  environment.
   */
  private[config] def loadFrom(env: String => Option[String]): Either[String, AppConfig] =
    for
      httpHost    <- Right(env("HTTP_HOST").getOrElse(DefaultHttpHost))
      httpPort    <- parsePort("HTTP_PORT", env("HTTP_PORT").getOrElse(DefaultHttpPort))
      wsEnabled   <- parseBool("WS_ENABLED", env("WS_ENABLED").getOrElse(DefaultWsEnabled))
      wsPort      <- parsePort("WS_PORT", env("WS_PORT").getOrElse(DefaultWsPort))
      persistence <- parsePersistenceMode(env("PERSISTENCE_MODE").getOrElse(DefaultPersistence))
      eventMode   <- parseEventMode(env("EVENT_MODE").getOrElse(DefaultEventMode))
      corsEnabled <- parseBool("CORS_ENABLED", env("CORS_ENABLED").getOrElse(DefaultCorsEnabled))
      corsOrigin   = env("CORS_ALLOWED_ORIGIN").getOrElse(DefaultCorsAllowOrigin)
      aiMode      <- parseAiProviderMode(env("AI_PROVIDER_MODE").getOrElse(DefaultAiMode))
      aiTimeout   <- parsePositiveInt("AI_TIMEOUT_MILLIS", env("AI_TIMEOUT_MILLIS").getOrElse(DefaultAiTimeoutMillis))
      remote      <- parseRemoteAiConfig(aiMode, env("AI_REMOTE_BASE_URL"))
      engineId     = env("AI_DEFAULT_ENGINE_ID")
    yield AppConfig(
      http        = HttpConfig(httpHost, httpPort),
      webSocket   = WebSocketConfig(wsEnabled, wsPort),
      persistence = persistence,
      eventMode   = eventMode,
      cors        = CorsConfig(corsEnabled, corsOrigin),
      ai          = AiConfig(
                      mode            = aiMode,
                      remote          = remote,
                      timeoutMillis   = aiTimeout,
                      defaultEngineId = engineId
                    )
    )

  /** Load config or print the error and exit the process.
   *
   *  Prints a clear message to stderr and calls `sys.exit(1)` on any
   *  validation failure.  For use in JVM entry points only.
   */
  def loadOrExit(): AppConfig =
    load().fold(
      err => { System.err.println(s"[chess] Configuration error: $err"); sys.exit(1) },
      identity
    )

  // ── Private parsers ──────────────────────────────────────────────────────────

  private def parsePort(varName: String, value: String): Either[String, Int] =
    value.toIntOption match
      case None                           => Left(s"$varName must be an integer, got: '$value'")
      case Some(p) if p < 1 || p > 65535 => Left(s"$varName must be between 1 and 65535, got: $p")
      case Some(p)                        => Right(p)

  private def parsePositiveInt(varName: String, value: String): Either[String, Int] =
    value.toIntOption match
      case None              => Left(s"$varName must be an integer, got: '$value'")
      case Some(n) if n < 1  => Left(s"$varName must be >= 1, got: $n")
      case Some(n)           => Right(n)

  private def parseBool(varName: String, value: String): Either[String, Boolean] =
    value.toLowerCase match
      case "true"  | "1" | "yes" => Right(true)
      case "false" | "0" | "no"  => Right(false)
      case _                     => Left(s"$varName must be true/false/1/0/yes/no, got: '$value'")

  private def parsePersistenceMode(value: String): Either[String, PersistenceMode] =
    value.toLowerCase match
      case "in-memory" | "inmemory" => Right(PersistenceMode.InMemory)
      case _                        => Left(s"PERSISTENCE_MODE must be 'in-memory', got: '$value'")

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
        Left(s"AI_PROVIDER_MODE must be 'local', 'disabled', or 'remote', got: '$value'")

  private def parseRemoteAiConfig(
    mode:    AiProviderMode,
    baseUrl: Option[String]
  ): Either[String, Option[RemoteAiConfig]] =
    mode match
      case AiProviderMode.Remote =>
        baseUrl match
          case Some(url) if url.trim.nonEmpty => Right(Some(RemoteAiConfig(url.trim)))
          case _ => Left("AI_REMOTE_BASE_URL is required when AI_PROVIDER_MODE is 'remote'")
      case _ =>
        Right(baseUrl.map(url => RemoteAiConfig(url.trim)).filter(_.baseUrl.nonEmpty))
