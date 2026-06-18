package chess.tournamentservice

import chess.observability.StructuredLog

object TournamentServiceMain:

  def main(args: Array[String]): Unit =
    val config = TournamentServiceConfig.loadOrExit()
    StructuredLog.info(
      "tournament-service",
      "startup_config",
      "httpHost" -> config.host,
      "httpPort" -> config.port,
      "outputBasePath" -> config.outputBasePath,
      "maxParallelJobs" -> config.maxParallelJobs
    )

    val runtime = TournamentServiceWiring.start(config)
    StructuredLog.info(
      "tournament-service",
      "started",
      "httpHost" -> config.host,
      "httpPort" -> config.port,
      "healthPath" -> "/health"
    )

    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      StructuredLog.info("tournament-service", "shutdown_started")
      runtime.shutdown()
      StructuredLog.info("tournament-service", "shutdown_completed")
    }))

    Thread.currentThread().join()
