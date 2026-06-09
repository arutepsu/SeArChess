package chess.lichessbridge

import chess.observability.StructuredLog

/** Runtime configuration for the Lichess Bridge service.
  *
  * All values are read from environment variables at startup.
  * Phase 1:    service is always disabled at runtime (LICHESS_BRIDGE_ENABLED defaults to false).
  * Phase 2A:   LICHESS_BOT_TOKEN is now read (but never logged).
  * Phase 2B-1: challenge policy keys added; all default to the most conservative values.
  *
  * Environment variables:
  *   LICHESS_BRIDGE_ENABLED      — must be "true" to enable bridge logic (default: false)
  *   LICHESS_API_BASE_URL        — Lichess API root (default: https://lichess.org)
  *   LICHESS_BOT_USERNAME        — Lichess bot account username (default: empty = not configured)
  *   LICHESS_BOT_TOKEN           — Lichess bot OAuth token (optional; never logged)
  *   AI_SERVICE_URL              — URL of the internal Searchess AI service (default: http://ai-service:8765)
  *   MAX_CONCURRENT_GAMES        — maximum simultaneous bridged games (default: 1)
  *   LICHESS_BRIDGE_HTTP_HOST    — bind address for the HTTP server (default: 0.0.0.0)
  *   LICHESS_BRIDGE_HTTP_PORT    — bind port for the HTTP server (default: 8090)
  *   LICHESS_ACCEPT_CHALLENGES   — must be "true" to auto-accept incoming challenges (default: false)
  *   LICHESS_ALLOWED_CHALLENGERS — comma-separated lowercase usernames; empty = accept all (default: "")
  *   LICHESS_ACCEPT_RATED        — must be "true" to accept rated games (default: false)
  *   LICHESS_ALLOWED_VARIANTS    — comma-separated variant keys (default: "standard")
  *   LICHESS_MIN_CLOCK_SECONDS   — minimum acceptable total clock in seconds (default: 180 = 3+0)
  *   LICHESS_MAX_CLOCK_SECONDS   — maximum acceptable total clock in seconds (default: 600 = 10+0)
  */
final case class LichessBridgeConfig(
    enabled: Boolean,
    lichessApiBaseUrl: String,
    lichessBotUsername: Option[String],
    lichessBotToken: Option[String],
    aiServiceUrl: String,
    maxConcurrentGames: Int,
    host: String,
    port: Int,
    // Phase 2B-1: challenge policy — all defaults are deliberately conservative
    acceptChallenges: Boolean = false,
    allowedChallengers: Set[String] = Set.empty,
    acceptRated: Boolean = false,
    allowedVariants: Set[String] = Set("standard"),
    minClockSeconds: Int = 180,
    maxClockSeconds: Int = 600
):
  /** True if a non-empty bot username was configured. */
  def botUsernameConfigured: Boolean = lichessBotUsername.isDefined

  /** True if a non-empty token was configured. Never exposes the token value. */
  def tokenConfigured: Boolean = lichessBotToken.isDefined

  /** Returns Right(token) if a token is present, or Left(error) when enabled=true and no token. */
  def requireToken(): Either[String, String] =
    lichessBotToken match
      case Some(token) => Right(token)
      case None =>
        if enabled then Left("Token required when bridge is enabled")
        else Right("")

object LichessBridgeConfig:
  def loadOrExit(): LichessBridgeConfig =
    load().fold(
      err => {
        StructuredLog.error("lichess-bridge-service", "configuration_error", "error" -> err)
        sys.exit(1)
      },
      identity
    )

  def load(
      env: String => Option[String] = key => Option(System.getenv(key)).filter(_.nonEmpty)
  ): Either[String, LichessBridgeConfig] =
    for port <- parsePort("LICHESS_BRIDGE_HTTP_PORT", env("LICHESS_BRIDGE_HTTP_PORT").getOrElse("8090"))
    yield LichessBridgeConfig(
      enabled            = env("LICHESS_BRIDGE_ENABLED").exists(_.toLowerCase == "true"),
      lichessApiBaseUrl  = env("LICHESS_API_BASE_URL").getOrElse("https://lichess.org"),
      lichessBotUsername = env("LICHESS_BOT_USERNAME"),
      lichessBotToken    = env("LICHESS_BOT_TOKEN"),
      aiServiceUrl       = env("AI_SERVICE_URL").getOrElse("http://ai-service:8765"),
      maxConcurrentGames = env("MAX_CONCURRENT_GAMES").flatMap(_.toIntOption).getOrElse(1),
      host               = env("LICHESS_BRIDGE_HTTP_HOST").getOrElse("0.0.0.0"),
      port               = port,
      acceptChallenges   = env("LICHESS_ACCEPT_CHALLENGES").exists(_.toLowerCase == "true"),
      allowedChallengers = env("LICHESS_ALLOWED_CHALLENGERS")
        .fold(Set.empty[String])(s => s.split(',').map(_.trim.toLowerCase).filter(_.nonEmpty).toSet),
      acceptRated      = env("LICHESS_ACCEPT_RATED").exists(_.toLowerCase == "true"),
      allowedVariants  = env("LICHESS_ALLOWED_VARIANTS")
        .fold(Set("standard"))(s => s.split(',').map(_.trim).filter(_.nonEmpty).toSet),
      minClockSeconds  = env("LICHESS_MIN_CLOCK_SECONDS").flatMap(_.toIntOption).getOrElse(180),
      maxClockSeconds  = env("LICHESS_MAX_CLOCK_SECONDS").flatMap(_.toIntOption).getOrElse(600)
    )

  private def parsePort(name: String, value: String): Either[String, Int] =
    value.toIntOption match
      case Some(p) if p >= 1 && p <= 65535 => Right(p)
      case Some(p)                          => Left(s"$name must be between 1 and 65535, got: $p")
      case None                             => Left(s"$name must be an integer, got: '$value'")
