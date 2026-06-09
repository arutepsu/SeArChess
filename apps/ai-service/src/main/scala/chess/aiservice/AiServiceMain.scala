package chess.aiservice

import chess.observability.StructuredLog

/** Independent AI Service entry point. */
object AiServiceMain:

  def main(args: Array[String]): Unit =
    val config = AiServiceConfig.loadOrExit()
    StructuredLog.info(
      "ai-service",
      "startup_config",
      "httpHost" -> config.host,
      "httpPort" -> config.port,
      "engineId" -> config.engineId
    )

    val runtime = AiServiceWiring.start(config)
    StructuredLog.info(
      "ai-service",
      "started",
      "httpHost" -> config.host,
      "httpPort" -> config.port,
      "healthPath" -> chess.adapter.ai.remote.RemoteAiServiceContract.HealthPath,
      "inferencePath" -> chess.adapter.ai.remote.RemoteAiServiceContract.MoveSuggestionsPath
    )
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      StructuredLog.info("ai-service", "shutdown_started")
      runtime.shutdown()
      StructuredLog.info("ai-service", "shutdown_completed")
    }))

    Thread.currentThread().join()
