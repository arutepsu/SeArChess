package chess.bot

/** Bot worker entry point.
  *
  * Polls game-service for pending bot turns, asks AI service for a move, and submits it.
  * Never contacts lichess.org or uses a Lichess token.
  *
  * Config:
  *   BOT_WORKER_API_KEY        — API key for authenticating to game-service (required)
  *                               Secret key name in k8s: bot-worker-api-key
  *   GAME_SERVICE_BASE_URL     — Base URL of game-service (default: http://game-service:8080)
  *   AI_SERVICE_BASE_URL       — Base URL of AI service (default: http://ai-service:8765)
  *   BOT_WORKER_ACTOR_ID       — Actor ID used when submitting moves (default: searchess-bot)
  *   BOT_POLL_INTERVAL_MILLIS  — Polling interval when no task found (default: 2000)
  *   BOT_MOVE_TIMEOUT_MILLIS   — Per-request HTTP timeout (default: 5000)
  */
object BotWorkerMain:

  def main(args: Array[String]): Unit =
    val apiKey = Option(System.getenv("BOT_WORKER_API_KEY"))
      .map(_.trim)
      .filter(_.nonEmpty)

    apiKey match
      case None =>
        System.err.println("[BotWorker] ERROR: BOT_WORKER_API_KEY is not set. Cannot authenticate to game-service.")
        sys.exit(1)

      case Some(key) =>
        val gameServiceUrl     = env("GAME_SERVICE_BASE_URL", "http://game-service:8080")
        val aiServiceUrl       = env("AI_SERVICE_BASE_URL", "http://ai-service:8765")
        val actorId            = env("BOT_WORKER_ACTOR_ID", "searchess-bot")
        val pollIntervalMillis = envInt("BOT_POLL_INTERVAL_MILLIS", 2000)
        val moveTimeoutMillis  = envInt("BOT_MOVE_TIMEOUT_MILLIS", 5000)

        if pollIntervalMillis <= 0 then
          System.err.println(
            s"[BotWorker] ERROR: BOT_POLL_INTERVAL_MILLIS must be positive, got: $pollIntervalMillis"
          )
          sys.exit(1)

        if moveTimeoutMillis <= 0 then
          System.err.println(
            s"[BotWorker] ERROR: BOT_MOVE_TIMEOUT_MILLIS must be positive, got: $moveTimeoutMillis"
          )
          sys.exit(1)

        println(
          s"[BotWorker] Starting. gameServiceUrl=$gameServiceUrl aiServiceUrl=$aiServiceUrl" +
            s" actorId=$actorId pollInterval=${pollIntervalMillis}ms moveTimeout=${moveTimeoutMillis}ms" +
            s" key=***${key.takeRight(4)}"
        )

        val worker = BotWorker(
          gameServiceUrl    = gameServiceUrl,
          aiServiceUrl      = aiServiceUrl,
          apiKey            = key,
          actorId           = actorId,
          moveTimeoutMillis = moveTimeoutMillis
        )

        while true do
          try
            val found = worker.runOnce()
            if !found then Thread.sleep(pollIntervalMillis.toLong)
          catch
            case _: InterruptedException =>
              println("[BotWorker] Interrupted, exiting.")
              sys.exit(0)
            case e: Exception =>
              System.err.println(s"[BotWorker] Unexpected error in poll loop: ${e.getMessage}")
              Thread.sleep(pollIntervalMillis.toLong)

  private def env(name: String, default: String): String =
    Option(System.getenv(name)).map(_.trim).filter(_.nonEmpty).getOrElse(default)

  private def envInt(name: String, default: Int): Int =
    Option(System.getenv(name)).map(_.trim).filter(_.nonEmpty).flatMap(_.toIntOption).getOrElse(default)
