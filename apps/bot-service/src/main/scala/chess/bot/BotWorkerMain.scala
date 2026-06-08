package chess.bot

/** Bot worker entry point.
  *
  * Reads configuration from environment, validates it, then waits.
  * Move polling and game-service integration are not yet implemented.
  *
  * Config:
  *   BOT_WORKER_API_KEY      — API key for authenticating to game-service (required)
  *                             Secret key name in k8s: external-game-bot-api-key
  *                             TODO: rename secret key to bot-worker-api-key in a follow-up step
  *   GAME_SERVICE_BASE_URL   — Base URL of game-service (default: http://game-service:8080)
  *   BOT_WORKER_ACTOR_ID     — Actor ID used when submitting moves (default: searchess-bot)
  */
object BotWorkerMain:

  def main(args: Array[String]): Unit =
    val apiKey = Option(System.getenv("BOT_WORKER_API_KEY"))
      .map(_.trim)
      .filter(_.nonEmpty)

    val gameServiceUrl = Option(System.getenv("GAME_SERVICE_BASE_URL"))
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse("http://game-service:8080")

    val actorId = Option(System.getenv("BOT_WORKER_ACTOR_ID"))
      .map(_.trim)
      .filter(_.nonEmpty)
      .getOrElse("searchess-bot")

    apiKey match
      case None =>
        System.err.println("[BotWorker] ERROR: BOT_WORKER_API_KEY is not set. Cannot authenticate to game-service.")
        sys.exit(1)

      case Some(key) =>
        println(s"[BotWorker] Initialized. gameServiceUrl=$gameServiceUrl actorId=$actorId key=***${key.takeRight(4)}")
        println("[BotWorker] Move polling not yet implemented. Worker is idle.")
        while true do
          Thread.sleep(60_000)
          println("[BotWorker] Idle heartbeat. Waiting for move polling implementation.")
