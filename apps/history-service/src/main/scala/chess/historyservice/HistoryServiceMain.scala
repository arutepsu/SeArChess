package chess.historyservice

import chess.observability.StructuredLog

object HistoryServiceMain:

  def main(args: Array[String]): Unit =
    val config = HistoryServiceConfig.loadOrExit()
    StructuredLog.info(
      "history-service",
      "startup_config",
      "httpHost" -> config.host,
      "httpPort" -> config.port,
      "gameServiceArchiveBaseUrl" -> config.gameServiceBaseUrl,
      "dbPath" -> config.dbPath,
      "acceptLegacyIngestionPath" -> config.acceptLegacyIngestionPath
    )

    val runtime = HistoryServiceWiring.start(config)
    StructuredLog.info(
      "history-service",
      "started",
      "httpHost" -> config.host,
      "httpPort" -> config.port,
      "healthPath" -> "/health",
      "downstreamIngestionPath" -> chess.adapter.event.GameHistoryIngestionContract.GameEventsPath,
      "legacyIngestionPathEnabled" -> config.acceptLegacyIngestionPath
    )
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      StructuredLog.info("history-service", "shutdown_started")
      runtime.shutdown()
      StructuredLog.info("history-service", "shutdown_completed")
    }))

    Thread.currentThread().join()
