package chess.aiservice

<<<<<<< HEAD
import chess.observability.StructuredLog

=======
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)
/** Independent AI Service entry point. */
object AiServiceMain:

  def main(args: Array[String]): Unit =
    val config = AiServiceConfig.loadOrExit()
<<<<<<< HEAD
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
=======
    println(s"[ai] Engine: ${config.engineId}")

    val runtime = AiServiceWiring.start(config)
    Runtime.getRuntime.addShutdownHook(new Thread(() => runtime.shutdown()))
>>>>>>> f7a07f01 (runnable mains, hardered event contracts)

    Thread.currentThread().join()
