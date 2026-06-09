package chess.lichessbridge

import chess.observability.StructuredLog

/** Lichess Bridge service entry point.
  *
  * Phase 2B-1: persistent event stream, challenge policy, worker lifecycle.
  * The token value is NEVER logged.
  *
  * Config (all read from env):
  *   LICHESS_BRIDGE_ENABLED      — bridge is active only when "true" (default: false)
  *   LICHESS_API_BASE_URL        — Lichess API root URL (default: https://lichess.org)
  *   LICHESS_BOT_USERNAME        — Lichess bot account username (optional)
  *   LICHESS_BOT_TOKEN           — Lichess bot OAuth token (optional; never logged)
  *   AI_SERVICE_URL              — internal Searchess AI service (default: http://ai-service:8765)
  *   MAX_CONCURRENT_GAMES        — maximum simultaneous bridged games (default: 1)
  *   LICHESS_BRIDGE_HTTP_HOST    — bind address (default: 0.0.0.0)
  *   LICHESS_BRIDGE_HTTP_PORT    — bind port (default: 8090)
  *   LICHESS_ACCEPT_CHALLENGES   — auto-accept incoming challenges (default: false)
  *   LICHESS_ALLOWED_CHALLENGERS — comma-separated usernames; empty = all (default: "")
  *   LICHESS_ACCEPT_RATED        — accept rated games (default: false)
  *   LICHESS_ALLOWED_VARIANTS    — comma-separated variants (default: "standard")
  *   LICHESS_MIN_CLOCK_SECONDS   — minimum clock (default: 180)
  *   LICHESS_MAX_CLOCK_SECONDS   — maximum clock (default: 600)
  */
object LichessBridgeMain:

  def main(args: Array[String]): Unit =
    val config = LichessBridgeConfig.loadOrExit()

    StructuredLog.info(
      "lichess-bridge-service",
      "startup_config",
      "enabled"                -> config.enabled,
      "lichessApiBaseUrl"      -> config.lichessApiBaseUrl,
      "botUsernameConfigured"  -> config.botUsernameConfigured,
      "tokenConfigured"        -> config.tokenConfigured,
      "aiServiceUrl"           -> config.aiServiceUrl,
      "maxConcurrentGames"     -> config.maxConcurrentGames,
      "acceptChallenges"       -> config.acceptChallenges,
      "acceptRated"            -> config.acceptRated,
      "allowedVariants"        -> config.allowedVariants.mkString(","),
      "minClockSeconds"        -> config.minClockSeconds,
      "maxClockSeconds"        -> config.maxClockSeconds,
      "httpHost"               -> config.host,
      "httpPort"               -> config.port,
      "phase"                  -> "2B-1"
    )

    val runtime = LichessBridgeWiring.start(config)

    StructuredLog.info(
      "lichess-bridge-service",
      "started",
      "httpHost"     -> config.host,
      "httpPort"     -> config.port,
      "healthPath"   -> "/health",
      "statusPath"   -> "/internal/lichess/status",
      "validatePath" -> "/internal/lichess/validate",
      "policyPath"   -> "/internal/lichess/policy"
    )

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      StructuredLog.info("lichess-bridge-service", "shutdown_started")
      runtime.shutdown()
      StructuredLog.info("lichess-bridge-service", "shutdown_completed")
    }))

    Thread.currentThread().join()
